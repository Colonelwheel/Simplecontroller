"""V3 receiver math/ownership/leases; fake clocks and outputs only."""
import math
import threading
import unittest
import uuid
from face_input_server import (FaceInputState, MergedDesktopInput, MergedGamepad,
                               ProtocolError, canonical_phone_key, CHANNELS, V3_OUTPUTS)
from test_face_input_server import Pad


class StickPad(Pad):
    def __init__(self):
        super().__init__()
        self.left = (0., 0.)

    def left_joystick_float(self, x_value_float, y_value_float):
        self.left = (x_value_float, y_value_float)


class MovementStateTests(unittest.TestCase):
    def setUp(self):
        self.now = 10.
        self.lock = threading.RLock()
        self.events = []
        self.held = set()
        def write(target, pressed):
            self.events.append((target, pressed))
            (self.held.add if pressed else self.held.discard)(target)
        self.desktop = MergedDesktopInput(write, self.lock)
        self.pad = MergedGamepad(StickPad(), {name: name for name in
            ('A', 'B', 'X', 'Y', 'LB', 'RB', 'START', 'BACK', 'LS', 'RS', 'UP', 'DOWN', 'LEFT', 'RIGHT')},
            self.lock, desktop=self.desktop, player='player1')
        self.other = MergedGamepad(StickPad(), self.pad.button_map, self.lock,
                                  desktop=self.desktop, player='player2')
        self.state = FaceInputState({'player1': self.pad, 'player2': self.other}, self.lock,
                                   clock=lambda: self.now)
        self.ready = self.state.handle({'v': 3, 'op': 'hello', 'session': str(uuid.uuid4())})
        self.epoch = self.ready['epoch']
        self.seq = self.frame = 0

    def send(self, op, channel=None, **fields):
        self.seq += 1
        return self.state.handle({'v': 3, 'op': op, 'epoch': self.epoch, 'seq': self.seq,
            **({'channel': channel} if channel else {}), **fields})

    def sample(self, channel='movement', **fields):
        self.frame += 1
        self.now += .001
        return self.send('sample', channel, frame_seq=self.frame, captured_at=self.now, **fields)

    def test_capability_and_unarmed_samples(self):
        self.assertIs(self.ready['movement_input'], True)
        self.sample(vector=[.3, .4], held=['KEY:W'])
        self.assertEqual((0., 0.), self.pad.raw.left)
        self.assertFalse(self.held)

    def test_circular_diagonal_and_phone_takeover_no_fallback(self):
        self.send('arm', 'movement')
        self.sample(vector=[math.sqrt(.5), math.sqrt(.5)])
        self.assertAlmostEqual(1., math.hypot(*self.pad.raw.left))
        self.pad.left_joystick_float(.7, -.2)
        self.assertEqual((.7, -.2), self.pad.raw.left)
        self.pad.left_joystick_float(0., 0.)
        self.assertEqual((0., 0.), self.pad.raw.left)
        self.sample(vector=[.5, .5])
        self.assertEqual((0., 0.), self.pad.raw.left)
        self.assertTrue(any(n['op'] == 'takeover' for n in self.state.drain_notifications()))
        self.send('arm', 'movement')
        self.sample(vector=[.5, .5])
        self.assertEqual((.5, .5), self.pad.raw.left)

    def test_takeover_clears_motion_boosts_preserves_button_wink(self):
        for channel in ('movement', 'nose_x', 'expression_brows', 'wink', 'mouth'):
            self.send('arm', channel)
            self.sample(channel, held=['A'] if channel == 'mouth' else ['KEY:W']
                        if channel in ('movement', 'nose_x', 'expression_brows') else ['B'])
        self.pad.left_joystick_float(.1, 0.)
        self.assertFalse(self.held)
        self.assertEqual({'A', 'B'}, self.pad.raw.buttons)

    def test_blocked_arm_does_not_replay_cached_vector(self):
        self.pad.left_joystick_float(.2, 0.)
        self.send('arm', 'movement')
        self.sample(vector=[0., 1.])
        self.pad.left_joystick_float(0., 0.)
        self.assertFalse(self.state.movement_channels['movement']['armed'])
        self.assertEqual((0., 0.), self.pad.raw.left)

    def test_250ms_lease_heartbeat_does_not_renew(self):
        self.send('arm', 'movement')
        self.sample(vector=[0., 1.], held=['KEY:W', 'RT'])
        capture = self.now
        self.now = capture + .249
        self.send('heartbeat')
        self.state.tick()
        self.assertEqual((0., 1.), self.pad.raw.left)
        self.now = capture + .250
        self.state.tick()
        self.assertEqual((0., 0.), self.pad.raw.left)
        self.assertFalse(self.held)
        self.assertEqual(0., self.pad.raw.triggers['RT'])
        self.assertFalse(self.state.movement_channels['movement']['armed'])
        self.assertTrue(any(n['op'] == 'expired' for n in self.state.drain_notifications()))

    def test_stale_sample_clears_instead_of_renewing_lease(self):
        self.send('arm', 'movement')
        self.sample(vector=[0., 1.])
        self.now += .4
        self.send('heartbeat')
        self.send('sample', 'movement', frame_seq=10, captured_at=self.now-.3,
                  vector=[1., 0.], held=['KEY:D'])
        self.assertEqual((0., 0.), self.pad.raw.left)
        self.assertFalse(self.held)

    def test_duplicate_or_out_of_order_capture_rejected(self):
        self.send('arm', 'movement')
        self.sample(vector=[0., .4])
        for fields in ({'frame_seq': self.frame, 'captured_at': self.now+.01},
                       {'frame_seq': self.frame+1, 'captured_at': self.now},
                       {'frame_seq': self.frame+1, 'captured_at': self.now+.01}):
            with self.subTest(fields=fields), self.assertRaises(ProtocolError):
                self.send('sample', 'movement', **fields)

    def test_invalid_sample_bounds_types_and_channels(self):
        self.send('arm', 'movement')
        cases = [dict(vector=[1.,1.]), dict(vector=[float('nan'), 0.]),
            dict(vector=[True, 0]), dict(held=['KEY:CTRL+W']), dict(held=['KEY:UNKNOWN']),
            dict(held=['KEY:W']*97), dict(taps=[{'id': 'x', 'targets':['A']}]*2),
            dict(taps=[{'id': 'x'*65, 'targets':['A']}])]
        for fields in cases:
            with self.subTest(fields=fields), self.assertRaises(ProtocolError):
                self.sample(**fields)
        with self.assertRaises(ProtocolError):
            self.send('arm', 'unbounded-channel')
        with self.assertRaises(ProtocolError):
            self.sample('wink', vector=[0.,.5])

    def test_full_diagonal_inherited_boost_union_and_held_cap(self):
        self.send('arm','expression_mouth')
        keys=sorted(target for target in V3_OUTPUTS if target.startswith('KEY:'))
        self.assertGreaterEqual(len(keys),72)
        self.sample('expression_mouth',held=keys[:72])
        self.assertGreaterEqual(len(self.held),68)  # generic modifiers alias left
        self.sample('expression_mouth',held=['KEY:W']*96)
        self.assertEqual({'KEY:W'},self.held)
        with self.assertRaises(ProtocolError):
            self.sample('expression_mouth',held=['KEY:W']*97)

    def test_claim_overlap_clear_and_tap_expiry_preserve_other_owners(self):
        self.desktop.phone_set('KEY:W', True)
        for channel in ('mouth','brows','wink'):
            self.send('arm', channel)
        self.sample('mouth', held=['KEY:W','A','RT'])
        self.sample('brows', held=['KEY:W','A','RT'])
        self.sample('wink', taps=[{'id':'wink1','targets':['KEY:W','A','RT']}])
        self.send('clear','mouth')
        self.now += .101
        self.state.tick()
        self.assertEqual({'KEY:W'}, self.held)
        self.assertEqual({'A'},self.pad.raw.buttons)
        self.assertEqual(1.,self.pad.raw.triggers['RT'])
        self.send('clear','brows')
        self.assertEqual({'KEY:W'},self.held)
        self.assertFalse(self.pad.raw.buttons)
        self.assertEqual(0.,self.pad.raw.triggers['RT'])
        self.desktop.phone_set('KEY:W',False)
        self.assertFalse(self.held)

    def test_receiver_tap_100ms_and_duplicate_action_id_does_not_extend(self):
        self.send('arm','wink')
        self.sample('wink',taps=[{'id':'event1','targets':['A']}])
        start=self.now
        self.now += .05
        self.sample('wink',taps=[{'id':'event1','targets':['A']}])
        self.now = start+.100
        self.state.tick()
        self.assertFalse(self.pad.raw.buttons)

    def test_scope_clear_keeps_other_axis_buttons_and_phone_vector(self):
        for channel in ('nose_x','nose_y','mouth'):
            self.send('arm',channel)
            self.sample(channel,held=['KEY:A'] if channel=='nose_x' else ['KEY:W']
                        if channel=='nose_y' else ['A'])
        self.send('clear','nose_x')
        self.assertEqual({'KEY:W'},self.held)
        self.assertEqual({'A'},self.pad.raw.buttons)
        self.assertFalse(self.state.movement_channels['nose_x']['armed'])

    def test_axis_clear_preserves_other_component_wasd_boost_and_original_lease(self):
        self.send('arm','movement')
        self.sample(vector=[.3,.4],held=['KEY:A','KEY:W','RT'],
                    taps=[{'id':'axis-tap','targets':['KEY:D','B']}])
        lease=self.state.movement_channels['movement']['lease']
        self.send('clear','movement',axes=['x'])
        self.assertEqual((0.,.4),self.pad.raw.left)
        self.assertEqual({'KEY:W'},self.held)
        self.assertEqual({'B'},self.pad.raw.buttons)
        self.assertEqual(1.,self.pad.raw.triggers['RT'])
        self.assertEqual(lease,self.state.movement_channels['movement']['lease'])
        self.assertTrue(self.state.movement_channels['movement']['armed'])
        self.now=lease
        self.send('heartbeat')
        self.state.tick()
        self.assertEqual((0.,0.),self.pad.raw.left)
        self.assertFalse(self.held)

    def test_axis_clear_rejects_invalid_scope_and_other_operations(self):
        for axes in ([],['x','x'],['z'],['x','y','x'],None,'x'):
            with self.subTest(axes=axes),self.assertRaises(ProtocolError):
                self.send('clear','movement',axes=axes)
        with self.assertRaises(ProtocolError):
            self.send('clear','nose_x',axes=['x'])
        with self.assertRaises(ProtocolError):
            self.send('arm','movement',axes=['x'])

    def test_keyboard_A_and_xbox_A_distinct_space_alias_same_owner(self):
        for channel in ('mouth','brows'):
            self.send('arm',channel)
        self.sample('mouth',held=['A','KEY:A','SPACE'])
        self.sample('brows',held=['KEY:SPACE'])
        self.send('clear','mouth')
        self.assertEqual({'SPACE'},self.held)
        self.assertFalse(self.pad.raw.buttons)

    def test_phone_source_watch_excludes_face_voice_and_other_player(self):
        self.send('watch',keys=['KEY:W','KEY:SPACE'],buttons=['A','RT'])
        first=self.state.drain_notifications()[0]
        self.assertEqual([],first['keys'])
        self.desktop.phone_set('KEY:W',True)
        self.pad.press_button('A')
        self.pad.right_trigger_float(.7)
        self.pad.voice_set('B',True)
        self.send('arm','mouth')
        self.sample('mouth',held=['KEY:SPACE'])
        self.state.tick()
        notice=[n for n in self.state.drain_notifications() if n['op']=='source'][-1]
        self.assertEqual(['KEY:W'],notice['keys'])
        self.assertEqual(['A','RT'],notice['buttons'])
        self.assertFalse(notice['stick_blocked'])
        self.pad.left_joystick_float(.3,0.)
        notice=[n for n in self.state.drain_notifications() if n['op']=='source'][-1]
        self.assertTrue(notice['stick_blocked'])
        self.assertEqual([.3,0.],notice['phone_stick'])

    def test_watch_rejects_wrong_namespace(self):
        for keys,buttons in [(['A'],[]),([],['KEY:W']),(['KEY:W']*97,[])]:
            with self.subTest(keys=keys,buttons=buttons),self.assertRaises(ProtocolError):
                self.send('watch',keys=keys,buttons=buttons)

    def test_reset_disconnect_timeout_clear_face_preserve_phone_voice(self):
        self.desktop.phone_set('KEY:W',True)
        self.pad.voice_set('A',True)
        self.send('arm','movement')
        self.sample(held=['KEY:W','B'],vector=[0.,.5])
        self.state.reset(('player1',))
        oldepoch=self.epoch
        self.assertEqual({'A'},self.pad.raw.buttons)
        self.assertEqual({'KEY:W'},self.held)
        self.assertEqual((0.,0.),self.pad.raw.left)
        self.assertEqual('reset',self.send('arm','movement')['op'])
        self.assertGreater(self.state.epoch,oldepoch)
        self.state.disconnect()
        self.assertIsNone(self.state.session)
        self.assertEqual({'KEY:W'},self.held)

    def test_heartbeat_timeout_independent_of_valid_samples(self):
        self.send('arm','movement')
        self.sample(vector=[.3,0.])
        self.now += .4
        self.sample(vector=[.4,0.])
        self.now += .101
        self.assertTrue(self.state.tick())
        self.assertEqual((0.,0.),self.pad.raw.left)
        self.assertIsNone(self.state.session)

    def test_generic_phone_bare_tap_and_restore_ownership(self):
        self.desktop.phone_set('KEY:W',True)
        self.desktop.phone_tap('KEY:W')
        self.assertNotIn('KEY:W',self.held)
        self.send('arm','mouth')
        self.sample('mouth',held=['KEY:W'])
        self.assertIn('KEY:W',self.held)
        self.send('clear','mouth')
        self.assertIn('KEY:W',self.held)
        self.desktop.phone_set('KEY:W',False)
        self.assertFalse(self.held)

    def test_native_release_failure_does_not_skip_other_claims_or_vector(self):
        self.send('arm','movement')
        self.sample(held=['KEY:W','KEY:D','A'],vector=[.4,0.])
        old=self.desktop.write
        def failing(target,pressed):
            if target=='KEY:W' and not pressed:
                raise OSError('fake release')
            old(target,pressed)
        self.desktop.face_write=failing
        with self.assertRaises(OSError):
            self.send('clear','movement')
        self.assertNotIn('KEY:D',self.held)
        self.assertFalse(self.pad.raw.buttons)
        self.assertEqual((0.,0.),self.pad.raw.left)
        self.assertIsNone(self.desktop.emitted['KEY:W'])
        self.desktop.face_write=old
        self.desktop.clear_unowned('KEY:W')
        self.assertFalse(self.held)

    def test_canonical_phone_keys_only_individual_approved_targets(self):
        self.assertEqual('KEY:W',canonical_phone_key('w'))
        self.assertEqual('KEY:LCTRL',canonical_phone_key('left ctrl'))
        self.assertEqual('SPACE',canonical_phone_key('spacebar'))
        self.assertIsNone(canonical_phone_key('ctrl+w'))
        self.assertIsNone(canonical_phone_key('WAIT_100'))

    def test_generic_and_left_modifier_aliases_share_ownership(self):
        for channel in ('mouth','brows'):
            self.send('arm',channel)
        self.sample('mouth',held=['KEY:CTRL'])
        self.sample('brows',held=['KEY:LCTRL'])
        self.send('clear','mouth')
        self.assertEqual({'KEY:LCTRL'},self.held)
        self.send('clear','brows')
        self.assertFalse(self.held)
        self.send('watch',keys=['KEY:CTRL','KEY:LCTRL'],buttons=[])
        self.state.drain_notifications()
        self.desktop.phone_set('KEY:CTRL',True)
        notice=self.state.drain_notifications()[-1]
        self.assertEqual(['KEY:CTRL','KEY:LCTRL'],notice['keys'])


if __name__=='__main__':
    unittest.main()
