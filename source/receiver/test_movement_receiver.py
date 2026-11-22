"""Production receiver entrypoint exercised with its existing native mocks."""
import ctypes
import threading
import unittest
import uuid
from unittest.mock import patch
from test_receiver_release_all import receiver, fake_keyboard, fake_pyautogui
from face_input_server import FACE_INPUT_TAG, keyboard_name


class MovementReceiverTests(unittest.TestCase):
    def setUp(self):
        receiver.face_input_state.disconnect()
        receiver.release_outputs(tuple(receiver.gamepads), 'movement mock isolation')
        fake_keyboard.down.clear()
        fake_keyboard.released.clear()
        self.clock = 10.
        self.seq = self.frame = 0
        self.tagged = []
        def fake_tagged(target, pressed):
            self.tagged.append((target, pressed))
            (fake_keyboard.press if pressed else fake_keyboard.release)(keyboard_name(target))
        self.native = patch.object(receiver, 'send_tagged_key', side_effect=fake_tagged)
        self.native.start()
        self.state = receiver.face_input_state
        self.oldclock = self.state.clock
        self.state.clock = lambda: self.clock
        self.epoch = self.state.handle({'v':3, 'op':'hello', 'session':str(uuid.uuid4())})['epoch']

    def tearDown(self):
        self.state.disconnect()
        receiver.release_outputs(tuple(receiver.gamepads), 'movement mock cleanup')
        self.state.clock = self.oldclock
        self.native.stop()

    def send(self,op,channel=None,**fields):
        self.seq+=1
        return self.state.handle({'v':3,'op':op,'epoch':self.epoch,'seq':self.seq,
            **({'channel':channel} if channel else {}),**fields})

    def sample(self,channel='movement',**fields):
        self.frame+=1
        self.clock+=.001
        return self.send('sample',channel,frame_seq=self.frame,captured_at=self.clock,**fields)

    def test_phone_hold_survives_face_wasd_clear_and_face_survives_phone_release(self):
        receiver.handle_key_press('W')
        self.send('arm','movement')
        self.sample(held=['KEY:W','KEY:A'])
        self.send('clear','movement')
        self.assertEqual({'w'},fake_keyboard.down)
        self.send('arm','movement')
        self.sample(held=['KEY:W'])
        receiver.handle_key_release('W')
        self.assertEqual({'w'},fake_keyboard.down)
        self.send('clear','movement')
        self.assertFalse(fake_keyboard.down)

    def test_face_key_is_tagged_phone_key_is_existing_backend(self):
        receiver.handle_key_press('W')
        self.assertFalse(self.tagged)
        self.send('arm','mouth')
        self.sample('mouth',held=['KEY:D','KEY:SPACE'])
        self.assertEqual({('KEY:D',True),('SPACE',True)},set(self.tagged))

    def test_bare_phone_tap_preserves_retained_key_and_face_restores_down(self):
        receiver.handle_key_press('W')
        receiver.handle_button_press('W')
        self.assertNotIn('w',fake_keyboard.down)
        self.assertIn('KEY:W',receiver.desktop_input.phone)
        self.send('arm','movement')
        self.sample(held=['KEY:W'])
        self.assertIn('w',fake_keyboard.down)
        self.send('clear','movement')
        self.assertIn('w',fake_keyboard.down)
        receiver.handle_key_release('W')
        self.assertNotIn('w',fake_keyboard.down)

    def test_phone_resync_restores_generic_ownership_without_releasing_face(self):
        receiver.handle_key_press('D')
        fake_keyboard.down.discard('d')
        receiver.clean_key_states()
        self.assertIn('KEY:D',receiver.desktop_input.phone)
        self.assertIn('d',fake_keyboard.down)
        self.send('arm','movement')
        self.sample(held=['KEY:D'])
        receiver.handle_key_release('D')
        self.assertIn('d',fake_keyboard.down)

    def test_phone_player2_cannot_own_desktop_keyboard(self):
        receiver.handle_key_press('W','player2')
        self.assertNotIn('KEY:W',receiver.desktop_input.phone)
        self.assertFalse(fake_keyboard.down)

    def test_keyboard_A_and_xbox_A_remain_independent(self):
        self.send('arm','mouth')
        self.sample('mouth',held=['KEY:A','A'])
        self.assertIn('a',fake_keyboard.down)
        self.assertIn('A',receiver.gamepads['player1'].buttons)
        receiver.handle_key_press('A')
        self.send('clear','mouth')
        self.assertIn('a',fake_keyboard.down)
        self.assertNotIn('A',receiver.gamepads['player1'].buttons)

    def test_face_ls_y_camera_follow_mapping_and_phone_priority(self):
        self.send('arm','movement')
        self.sample(vector=[.3,.4])
        pad=receiver.gamepads['player1']
        follow=receiver.camera_follow_states['player1']
        self.assertEqual((.3,.4),pad.left_stick)
        self.assertEqual((.3,-.4),(follow.left_x,follow.left_y))
        receiver.handle_stick_input(-.2,.6,'LEFT')
        self.assertEqual((-.2,-.6),pad.left_stick)
        self.assertEqual((-.2,.6),(follow.left_x,follow.left_y))
        receiver.handle_stick_input(0.,0.,'LEFT')
        self.assertEqual((0.,0.),pad.left_stick)
        self.sample(vector=[.3,.4])
        self.assertEqual((0.,0.),pad.left_stick)

    def test_facial_packets_do_not_touch_phone_bookkeeping(self):
        receiver.active_connections.clear()
        before=dict(receiver.camera_follow_last_input_at)
        before_order=dict(receiver.latest_stick_packets)
        self.send('arm','movement')
        self.sample(vector=[.3,.4],held=['KEY:W'])
        self.send('heartbeat')
        self.assertEqual({},receiver.active_connections)
        self.assertEqual(before,receiver.camera_follow_last_input_at)
        self.assertEqual(before_order,receiver.latest_stick_packets)

    def test_phone_timeout_clears_stale_rs_but_keeps_fresh_face_ls_follow(self):
        self.send('arm','movement')
        self.sample(vector=[.7,0.])
        follow=receiver.camera_follow_states['player1']
        follow.set_manual_right_stick(.6,.2)
        receiver.camera_follow_last_input_at['player1']=.001
        receiver.camera_follow_input_lost['player1']=False
        class OneCycle:
            def __init__(self): self.calls=0
            def wait(self,delay):
                self.calls+=1
                return self.calls>1
        with patch.object(receiver,'camera_follow_stop_event',OneCycle()), patch.object(receiver.time,'monotonic',return_value=6.):
            receiver.camera_follow_worker()
        self.assertEqual((.7,0.),receiver.gamepads['player1'].left_stick)
        self.assertEqual(.7,follow.left_x)
        self.assertFalse(follow.manual_rs_active)
        self.assertTrue(receiver.camera_follow_input_lost['player1'])

    def test_macro_then_manual_rs_priorities_survive_facial_ls(self):
        receiver.set_camera_follow_enabled('player1',True)
        self.send('arm','movement')
        self.sample(vector=[.7,0.])
        receiver.handle_stick_input(.4,.2,'RIGHT',source='manual')
        self.assertEqual((.4,-.2),receiver.gamepads['player1'].right_stick)
        receiver.handle_stick_input(-.6,.3,'RIGHT',source='macro')
        self.assertEqual((-.6,-.3),receiver.gamepads['player1'].right_stick)
        self.sample(vector=[-.7,0.])
        receiver.apply_camera_follow_output('player1',force=True)
        self.assertEqual((-.6,-.3),receiver.gamepads['player1'].right_stick)

    def test_phone_shortcut_snapshots_preserve_bare_tap_down_up_and_exclude_face(self):
        self.send('watch',keys=['KEY:W','KEY:SPACE'],buttons=['A'])
        self.state.drain_notifications()
        self.send('arm','mouth')
        self.sample('mouth',held=['KEY:SPACE','A'])
        receiver.handle_button_press('W')
        notices=self.state.drain_notifications()
        self.assertEqual([['KEY:W'],[]],[n['keys'] for n in notices if n['op']=='source'])
        self.assertTrue(all(n.get('buttons',[])==[] for n in notices))

    def test_mixed_shortcut_phone_sources_independent_of_face_voice(self):
        self.send('watch',keys=['KEY:SHIFT'],buttons=['A'])
        self.state.drain_notifications()
        receiver.handle_key_press('SHIFT')
        receiver.handle_button_press('X360A_HOLD')
        receiver.gamepads['player1'].voice_set('B',True)
        notices=self.state.drain_notifications()
        final=[n for n in notices if n['op']=='source'][-1]
        self.assertEqual(['KEY:SHIFT'],final['keys'])
        self.assertEqual(['A'],final['buttons'])

    def test_slow_shortcut_consumer_does_not_block_phone_action_and_face_clears(self):
        self.send('watch',keys=['KEY:W'],buttons=[])
        self.send('arm','movement')
        self.sample(vector=[.3,0.])
        for _ in range(12):
            receiver.handle_key_press('W')
            receiver.handle_key_release('W')
        self.assertTrue(self.state.source_overflow)
        self.assertNotIn('w',fake_keyboard.down)
        self.assertTrue(self.state.tick())
        self.assertIsNone(self.state.session)
        self.assertEqual((0.,0.),receiver.gamepads['player1'].left_stick)

    def test_global_release_invalidates_taps_holds_vectors_and_queued_epoch(self):
        receiver.handle_key_press('W')
        receiver.handle_stick_input(.3,.2,'LEFT')
        self.send('arm','mouth')
        self.sample('mouth',held=['KEY:D'],taps=[{'id':'once','targets':['A']}])
        receiver.release_outputs(tuple(receiver.gamepads),'movement global regression')
        self.assertFalse(fake_keyboard.down)
        self.assertFalse(receiver.gamepads['player1'].buttons)
        self.assertEqual((0.,0.),receiver.gamepads['player1'].left_stick)
        self.assertEqual('reset',self.send('sample','mouth',frame_seq=999,captured_at=self.clock,held=['B'])['op'])
        self.clock+=.2
        self.state.tick()
        self.assertFalse(receiver.gamepads['player1'].buttons)

    def test_keyboard_failure_cleanup_continues_mouse_xbox_and_pending_retry(self):
        self.send('arm','mouth')
        self.sample('mouth',held=['KEY:W','KEY:D','A','MOUSE_LEFT'])
        original=receiver.send_tagged_key
        def failing(target,pressed):
            if target=='KEY:W' and not pressed:
                raise OSError('synthetic release failure')
            original(target,pressed)
        with patch.object(receiver,'send_tagged_key',side_effect=failing):
            receiver.release_outputs(tuple(receiver.gamepads),'movement failure test')
        self.assertNotIn('d',fake_keyboard.down)
        self.assertFalse(fake_pyautogui.down)
        self.assertFalse(receiver.gamepads['player1'].buttons)
        receiver.release_outputs(tuple(receiver.gamepads),'movement retry test')
        self.assertFalse(fake_keyboard.down)


class TaggedAbiTests(unittest.TestCase):
    def test_win32_keyboard_abi_marker_and_release_flags_without_native_injection(self):
        # WinDLL is replaced before lazy ABI creation, so no OS API is called.
        calls=[]
        class Send:
            def __call__(self,count,pointer,size):
                calls.append((count,pointer._obj.type,pointer._obj.ki.wVk,
                              pointer._obj.ki.dwFlags,pointer._obj.ki.dwExtraInfo,size))
                return 1
        class Library:
            SendInput=Send()
        old=receiver._tagged_keyboard_api
        receiver._tagged_keyboard_api=None
        try:
            with patch.object(ctypes,'WinDLL',return_value=Library()):
                receiver.send_tagged_key('KEY:W',True)
                receiver.send_tagged_key('KEY:RCTRL',False)
            self.assertEqual((1,1,ord('W'),0,FACE_INPUT_TAG,40),calls[0])
            self.assertEqual((1,1,0xA3,3,FACE_INPUT_TAG,40),calls[1])
        finally:
            receiver._tagged_keyboard_api=old

    def test_sendinput_zero_is_visible_failure(self):
        class Send:
            def __call__(self,*args): return 0
        class Library:
            SendInput=Send()
        old=receiver._tagged_keyboard_api
        receiver._tagged_keyboard_api=None
        try:
            with patch.object(ctypes,'WinDLL',return_value=Library()),self.assertRaises(OSError):
                receiver.send_tagged_key('KEY:W',True)
        finally:
            receiver._tagged_keyboard_api=old


if __name__=='__main__':
    unittest.main()
