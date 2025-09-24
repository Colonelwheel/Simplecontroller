#include <WiFi.h>
#include <WiFiUdp.h>
#include <WiFiClient.h>
#include <WiFiServer.h>
#define CFG_TUD_HID 2  // enable up to two HID interfaces
#include <Adafruit_TinyUSB.h>
// For normal use, expose both KM + Gamepad (set to 0). Set to 1 for gamepad-only debug.
#ifndef GAMEPAD_ONLY
#define GAMEPAD_ONLY 1
#endif
#ifndef HID_PROFILE_SWITCH
// Enable Switch-style HID profile tweaks (descriptor usage Joystick vs Game Pad)
#define HID_PROFILE_SWITCH 1
#endif
#ifndef SWITCH_USAGE_JOYSTICK
// When HID_PROFILE_SWITCH is enabled, choose Joystick (1) or Game Pad (0) usage
#define SWITCH_USAGE_JOYSTICK 0
#endif
#include <RP2040.h>

// ===== Network (Static IP) =====
const char* STA_SSID = "Missy";
const char* STA_PASS = "pat.1975";
IPAddress ip(192,168,0,142);
IPAddress gateway(192,168,0,1);
IPAddress subnet(255,255,255,0);
const char* AP_SSID  = "ConsoleBridge-Setup";
const char* AP_PASS  = "";

const uint16_t CB_PORT = 9010;
WiFiUDP udp;
WiFiServer http(80);

// ===== Tunables =====
#define CB_MOUSE_SCALE 0.10f   // lower => less sensitive

// ===== LED =====
#ifndef LED_BUILTIN
#define LED_BUILTIN 25
#endif
enum LedPat { LED_BOOT, LED_WIFI_CONNECTING, LED_STA, LED_AP, LED_ERROR };
LedPat currentPat = LED_BOOT; unsigned long patNext=0; int patIndex=0; bool patOn=false;
void setPattern(LedPat p){ currentPat=p; patIndex=0; patOn=false; patNext=millis(); }
void ledWrite(bool on){ pinMode(LED_BUILTIN, OUTPUT); digitalWrite(LED_BUILTIN, on?HIGH:LOW); }
void ledTask(){ static const uint16_t BOOT_SEQ[]={100,100,100,700}; static const uint16_t CONN_SEQ[]={120,120,120,600}; static const uint16_t STA_SEQ[]={2000,60}; static const uint16_t AP_SEQ[]={200,1000}; static const uint16_t ERR_SEQ[]={150,150,150,150,150,800}; const uint16_t* seq=BOOT_SEQ; int len=2; switch(currentPat){case LED_BOOT:seq=BOOT_SEQ;len=2;break;case LED_WIFI_CONNECTING:seq=CONN_SEQ;len=2;break;case LED_STA:seq=STA_SEQ;len=1;break;case LED_AP:seq=AP_SEQ;len=2;break;default:seq=ERR_SEQ;len=3;break;} unsigned long now=millis(); if(now<patNext)return; patOn=!patOn; ledWrite(patOn); uint16_t dur=seq[patIndex% (len* (currentPat==LED_BOOT||currentPat==LED_WIFI_CONNECTING||currentPat==LED_AP?2:1))]; patNext=now+dur; patIndex++;}

// ===== HID =====
// Gamepad-only mode for diagnosis to ensure hosts see a controller.
#if GAMEPAD_ONLY
  #define RID_GP    1
  // Custom Gamepad report descriptor (7 bytes): 16 buttons, hat(4b + 4b pad), X Y Z Rz int8
  // Switch-style tweak: expose as Joystick usage when SWITCH_USAGE_JOYSTICK=1
  #if HID_PROFILE_SWITCH && SWITCH_USAGE_JOYSTICK
  #define HID_USAGE_CODE 0x04 /* Joystick */
  #else
  #define HID_USAGE_CODE 0x05 /* Game Pad */
  #endif
  static uint8_t const hid_desc_gp[] = {
    0x05, 0x01,       // USAGE_PAGE (Generic Desktop)
    0x09, HID_USAGE_CODE, // USAGE (Joystick/Game Pad)
    0xA1, 0x01,       // COLLECTION (Application)
      0x85, RID_GP,   //   REPORT_ID (RID_GP)
      // Buttons (16)
      0x05, 0x09,     //   USAGE_PAGE (Button)
      0x19, 0x01,     //   USAGE_MINIMUM (Button 1)
      0x29, 0x10,     //   USAGE_MAXIMUM (Button 16)
      0x15, 0x00,     //   LOGICAL_MINIMUM (0)
      0x25, 0x01,     //   LOGICAL_MAXIMUM (1)
      0x95, 0x10,     //   REPORT_COUNT (16)
      0x75, 0x01,     //   REPORT_SIZE (1)
      0x81, 0x02,     //   INPUT (Data,Var,Abs)
      // Hat switch (4 bits) with Null state (8 = center), then 4-bit padding
      0x05, 0x01,     //   USAGE_PAGE (Generic Desktop)
      0x09, 0x39,     //   USAGE (Hat switch)
      0x15, 0x00,     //   LOGICAL_MINIMUM (0)
      0x25, 0x07,     //   LOGICAL_MAXIMUM (7)
      0x35, 0x00,     //   PHYSICAL_MINIMUM (0)
      0x46, 0x3B, 0x01, // PHYSICAL_MAXIMUM (315)
      0x65, 0x14,     //   UNIT (Eng Rot)
      0x75, 0x04,     //   REPORT_SIZE (4)
      0x95, 0x01,     //   REPORT_COUNT (1)
      0x81, 0x42,     //   INPUT (Data,Var,Abs,Null)
      0x65, 0x00,     //   UNIT (None)
      0x75, 0x04,     //   REPORT_SIZE (4)
      0x95, 0x01,     //   REPORT_COUNT (1)
      0x81, 0x03,     //   INPUT (Const,Var,Abs) - padding
      // Axes X, Y, Rx, Ry as int8 [-127..127]
      0x05, 0x01,     //   USAGE_PAGE (Generic Desktop)
      0x09, 0x30,     //   USAGE (X)
      0x09, 0x31,     //   USAGE (Y)
      0x09, 0x33,     //   USAGE (Rx)  [Right stick X]
      0x09, 0x34,     //   USAGE (Ry)  [Right stick Y]
      0x15, 0x81,     //   LOGICAL_MINIMUM (-127)
      0x25, 0x7F,     //   LOGICAL_MAXIMUM (127)
      0x75, 0x08,     //   REPORT_SIZE (8)
      0x95, 0x04,     //   REPORT_COUNT (4)
      0x81, 0x02,     //   INPUT (Data,Var,Abs)
    0xC0              // END_COLLECTION
  };
  Adafruit_USBD_HID usb_hid_gp(hid_desc_gp, sizeof(hid_desc_gp), HID_ITF_PROTOCOL_NONE, 1, false);
#else
  // Two interfaces: one for keyboard+mouse, one for gamepad.
  #define RID_KBD   1
  #define RID_MOUSE 2
  static uint8_t const hid_desc_km[] = {
    TUD_HID_REPORT_DESC_KEYBOARD(HID_REPORT_ID(RID_KBD)),
    TUD_HID_REPORT_DESC_MOUSE   (HID_REPORT_ID(RID_MOUSE))
  };
  #define RID_GP    1
  static uint8_t const hid_desc_gp[] = {
    TUD_HID_REPORT_DESC_GAMEPAD (HID_REPORT_ID(RID_GP))
  };
  Adafruit_USBD_HID usb_hid_km(hid_desc_km, sizeof(hid_desc_km), HID_ITF_PROTOCOL_NONE, 2, false);
  Adafruit_USBD_HID usb_hid_gp(hid_desc_gp, sizeof(hid_desc_gp), HID_ITF_PROTOCOL_NONE, 1, false);
#endif
typedef struct __attribute__((packed)) { uint8_t modifier; uint8_t reserved; uint8_t keycode[6]; } hid_kbd_report_t;
hid_kbd_report_t kbd = {0,0,{0,0,0,0,0,0}};
uint8_t mouse_buttons = 0;
/* ===== HID pacing & smoothing ===== */
#define TX_HZ 125             // 60–125 Hz works well; start at 125, try 100 if needed

// Coalesced mouse deltas (accumulate; send once per frame)
static volatile int16_t acc_dx = 0, acc_dy = 0;
static volatile bool mouse_dirty = false;

// Mark when any gamepad field changed since last frame
static volatile bool gp_dirty = false;

// Next frame deadline
static unsigned long next_hid_ms = 0;

// Optional: light smoothing to reduce micro-jitter (alpha 0..255; higher = smoother)
static inline int8_t lerp8(int8_t a, int8_t b, uint8_t alpha) {
  return a + ((int16_t)(b - a) * alpha) / 255;
}

/* ===== Verbose logging gate (OFF by default) ===== */
#define VERBOSE 0
#if VERBOSE
  #define VLOG(...) do { Serial.printf(__VA_ARGS__); } while(0)
#else
  #define VLOG(...) do {} while(0)
#endif

enum { MOD_LCTRL=0x01, MOD_LSHIFT=0x02, MOD_LALT=0x04, MOD_LGUI=0x08 };
static void waitHIDReady(){ uint32_t t0=millis(); while(!TinyUSBDevice.mounted() && (millis()-t0)<5000) delay(10); }
static uint32_t activity_off_at=0; static void blinkActivity(uint16_t ms){ digitalWrite(LED_BUILTIN,HIGH); activity_off_at = millis() + ms; }
static inline void activityLedService(){
  if(activity_off_at && (int32_t)(millis() - activity_off_at) >= 0){
    digitalWrite(LED_BUILTIN, LOW);
    activity_off_at = 0;
  }
}

void kbdSend(){
#if !GAMEPAD_ONLY
  usb_hid_km.sendReport(RID_KBD, &kbd, sizeof(kbd));
#endif
}
void mouseSend(int8_t dx,int8_t dy){
#if !GAMEPAD_ONLY
  uint8_t rpt[5]={mouse_buttons,(uint8_t)dx,(uint8_t)dy,0,0};
  usb_hid_km.sendReport(RID_MOUSE,rpt,sizeof(rpt));
#endif
}
void mousePress(uint8_t m){ mouse_buttons|=m; mouseSend(0,0); }
void mouseRelease(uint8_t m){ mouse_buttons&=~m; mouseSend(0,0); }

// ===== Gamepad (generic HID) =====
typedef struct __attribute__((packed)) {
  uint16_t buttons; // 16 buttons
  uint8_t  hat;     // POV hat: 0..7, 8 = centered
  int8_t   x;       // LS X   [-127..127]
  int8_t   y;       // LS Y   [-127..127]
  int8_t   z;       // RS X   [-127..127]
  int8_t   rz;      // RS Y   [-127..127]
} hid_gp_report_t;

static hid_gp_report_t gp = {0, 8, 0, 0, 0, 0};
static inline int8_t clamp_i16_to_i8(int16_t v){ if(v>127) return 127; if(v<-128) return -128; return (int8_t)v; }
static inline int8_t scale_i16_to_i8(int16_t v){ // 32767 -> ~127
  int32_t t = v; t /= 256; if(t>127) t=127; if(t<-128) t=-128; return (int8_t)t;
}
void gpSend(){
  // ensure neutral hat when not explicitly set
  if(gp.hat > 8) gp.hat = 8;
  bool ok = usb_hid_gp.sendReport(RID_GP, &gp, sizeof(gp));
  if(!ok){ Serial.println("GP send failed (EP busy)"); }
}
void gpSetButton(int id, bool down){ gpSetButtonDeferred(id, down); }


/* ---------- Mouse aggregation ---------- */
static inline void processMouseDelta(int32_t dx, int32_t dy) {
  // Clamp accumulated range to HID 8-bit (–128..127)
  int32_t nx = (int32_t)acc_dx + dx;
  int32_t ny = (int32_t)acc_dy + dy;
  if (nx > 127) nx = 127; else if (nx < -128) nx = -128;
  if (ny > 127) ny = 127; else if (ny < -128) ny = -128;
  acc_dx = (int16_t)nx;
  acc_dy = (int16_t)ny;
  mouse_dirty = true;
}

/* ---------- Gamepad setters (defer send; mark dirty) ---------- */
static inline void gpSetButtonDeferred(int id, bool down) {
  if (id < 0 || id > 15) return;
  uint16_t mask = (1u << id);
  uint16_t prev = gp.buttons;
  if (down) gp.buttons |= mask; else gp.buttons &= ~mask;
  if (gp.buttons != prev) gp_dirty = true;
}

static inline void gpSetStickDeferred(int whichLR_0forLS_1forRS, int8_t x8, int8_t y8) {
  auto dz = [](int8_t v){ return (v >= -4 && v <= 4) ? (int8_t)0 : v; };
  x8 = dz(x8); y8 = dz(y8);
  if (whichLR_0forLS_1forRS == 0) {
    // Flip LS Y so up is negative (match expected game semantics)
    gp.x  = lerp8(gp.x,  x8,   200);
    gp.y  = lerp8(gp.y, (int8_t)-y8, 200);
  } else {
    // Right stick: Rx (X) and Ry (Y). Flip Y so up is negative like LS.
    gp.z  = lerp8(gp.z,  x8,        200);
    gp.rz = lerp8(gp.rz, (int8_t)-y8, 200);
  }
  gp_dirty = true;
}

/* ---------- Fixed-rate HID tick ---------- */
static void hidTick() {
  unsigned long now = millis();
  if (now < next_hid_ms) return;
  next_hid_ms = now + (1000 / TX_HZ);

  // Mouse (coalesced)
  if (mouse_dirty) {
    int8_t dx = (acc_dx > 127 ? 127 : (acc_dx < -128 ? -128 : (int8_t)acc_dx));
    int8_t dy = (acc_dy > 127 ? 127 : (acc_dy < -128 ? -128 : (int8_t)acc_dy));
    if (dx || dy) {
      mouseSend(dx, dy);
      VLOG("[HID] mouse dx=%d dy=%d\n", dx, dy);
    }
    acc_dx = acc_dy = 0;
    mouse_dirty = false;
  }

  // Gamepad (only if something changed)
  if (gp_dirty) {
    if (gp.hat > 8) gp.hat = 8;
    bool ok = usb_hid_gp.sendReport(RID_GP, &gp, sizeof(gp));
    (void)ok;
    VLOG("[HID] gp frame sent\n");
    gp_dirty = false;
  }
}
// ===== Legacy ASCII (fallback) =====
static inline String trimCopy(const String& s){ int i=0,j=s.length()-1; while(i<=j&&isspace((unsigned char)s[i])) i++; while(j>=i&&(isspace((unsigned char)s[j])||s[j]==',')) j--; return (j<i)?String(""):s.substring(i,j+1); }
static inline bool eqNoCase(const String& a, const char* b){ String aa=a; aa.toUpperCase(); String bb=b; for(size_t i=0;i<bb.length();++i) bb[i]=toupper(bb[i]); return aa==bb; }

static String canonicalToken(const String& s){
  String t; t.reserve(s.length());
  for(size_t i=0;i<s.length();++i){ char c=s[i]; if(isalnum((unsigned char)c)) t += (char)toupper((unsigned char)c); }
  return t;
}

static int gpIdForName(const String& name){
  String n = canonicalToken(name);
  if(n=="A"||n=="BTNA"||n=="SOUTH"||n=="BTNSOUTH"||n=="CROSS") return 0;
  if(n=="B"||n=="BTNB"||n=="EAST" ||n=="BTNEAST" ||n=="CIRCLE") return 1;
  if(n=="X"||n=="BTNX"||n=="WEST" ||n=="BTNWEST" ||n=="SQUARE") return 2;
  if(n=="Y"||n=="BTNY"||n=="NORTH"||n=="BTNNORTH"||n=="TRIANGLE") return 3;
  if(n=="LB"||n=="L1"||n=="LEFTBUMPER") return 4;
  if(n=="RB"||n=="R1"||n=="RIGHTBUMPER") return 5;
  if(n=="BACK"||n=="SELECT"||n=="MINUS") return 6;
  if(n=="START"||n=="OPTIONS"||n=="PLUS") return 7;
  if(n=="LS"||n=="L3"||n=="LSC"||n=="LEFTSTICK") return 8;
  if(n=="RS"||n=="R3"||n=="RSC"||n=="RIGHTSTICK") return 9;
  if(n=="LT"||n=="L2"||n=="LEFTTRIGGER") return 10;
  if(n=="RT"||n=="R2"||n=="RIGHTTRIGGER") return 11;
  if(n=="DPADUP"||n=="HATUP"||n=="UP") return 12;
  if(n=="DPADDOWN"||n=="HATDOWN"||n=="DOWN") return 13;
  if(n=="DPADLEFT"||n=="HATLEFT"||n=="LEFT") return 14;
  if(n=="DPADRIGHT"||n=="HATRIGHT"||n=="RIGHT") return 15;
  return -1;
}

static bool parseX360Key(const String& key, int& gid, uint8_t& opOut){
  // Robust parse: uppercase + strip non-alnum so X360X_HOLD → X360XHOLD
  String c = canonicalToken(key);
  Serial.print("X360 parse canonical='"); Serial.print(c); Serial.println("'");
  if(c.length() < 5) return false;
  if(!(c[0]=='X' && c[1]=='3' && c[2]=='6' && c[3]=='0')) return false;
  String rest = c.substring(4);
  Serial.print("X360 rest='"); Serial.print(rest); Serial.println("'");

  // Determine op by suffix tokens HOLD/RELEASE if present
  uint8_t op = 2; // default press
  if(rest.endsWith("HOLD")) { op = 0; rest.remove(rest.length()-4); }
  else if(rest.endsWith("RELEASE")) { op = 1; rest.remove(rest.length()-7); }
  Serial.print("X360 base='"); Serial.print(rest); Serial.print("' op="); Serial.println(op);

  // Map rest to button id
  if(rest=="A") gid = 0;
  else if(rest=="B") gid = 1;
  else if(rest=="X") gid = 2;
  else if(rest=="Y") gid = 3;
  else if(rest=="LB"||rest=="L1") gid = 4;
  else if(rest=="RB"||rest=="R1") gid = 5;
  else if(rest=="BACK"||rest=="SELECT"||rest=="MINUS") gid = 6;
  else if(rest=="START"||rest=="OPTIONS"||rest=="PLUS") gid = 7;
  else if(rest=="LSC"||rest=="L3"||rest=="LS") gid = 8;
  else if(rest=="RSC"||rest=="R3"||rest=="RS") gid = 9;
  else return false;

  opOut = op;
  return true;
}

static bool parseX360Dpad(const String& key, uint8_t& hatOut, uint8_t& opOut){
  // Map X360 dpad tokens to hat values: 0=Up, 2=Right, 4=Down, 6=Left; 8=center
  String c = canonicalToken(key);
  if(c.length() < 6) return false;
  if(!(c[0]=='X' && c[1]=='3' && c[2]=='6' && c[3]=='0')) return false;
  String rest = c.substring(4);
  uint8_t op = 2; // default press
  if(rest.endsWith("HOLD")) { op = 0; rest.remove(rest.length()-4); }
  else if(rest.endsWith("RELEASE")) { op = 1; rest.remove(rest.length()-7); }
  if     (rest=="UP")    hatOut = 0;
  else if(rest=="RIGHT") hatOut = 2;
  else if(rest=="DOWN")  hatOut = 4;
  else if(rest=="LEFT")  hatOut = 6;
  else return false;
  opOut = op;
  return true;
}

uint8_t keyForName(const String& name, uint8_t& mods){
  mods=0; String n=name; n.toUpperCase();
  if(n=="SHIFT"){mods|=MOD_LSHIFT; return 0;}
  if(n=="CTRL"||n=="CONTROL"){mods|=MOD_LCTRL; return 0;}
  if(n=="ALT"){mods|=MOD_LALT; return 0;}
  if(n=="GUI"||n=="WIN"||n=="META"){mods|=MOD_LGUI; return 0;}
  if(n=="ENTER"||n=="RETURN") return HID_KEY_ENTER;
  if(n=="ESC"||n=="ESCAPE")   return HID_KEY_ESCAPE;
  if(n=="BACKSPACE")          return HID_KEY_BACKSPACE;
  if(n=="TAB")                return HID_KEY_TAB;
  if(n=="SPACE")              return HID_KEY_SPACE;
  if(n=="ARROW_LEFT"||n=="LEFT")   return HID_KEY_ARROW_LEFT;
  if(n=="ARROW_RIGHT"||n=="RIGHT") return HID_KEY_ARROW_RIGHT;
  if(n=="ARROW_UP"||n=="UP")       return HID_KEY_ARROW_UP;
  if(n=="ARROW_DOWN"||n=="DOWN")   return HID_KEY_ARROW_DOWN;
  if(n.length()==1){ char c=n[0]; if(c>='A'&&c<='Z') return HID_KEY_A+(c-'A'); if(c>='0'&&c<='9'){ if(c=='0')return HID_KEY_0; return HID_KEY_1+(c-'1'); } }
  return 0;
}
void kbdPress(uint8_t kc,uint8_t mods){ kbd.modifier|=mods; if(kc){for(int i=0;i<6;i++)if(kbd.keycode[i]==kc)return; for(int i=0;i<6;i++)if(kbd.keycode[i]==0){kbd.keycode[i]=kc;break;}} kbdSend(); }
void kbdRelease(uint8_t kc,uint8_t mods){ if(mods)kbd.modifier&=~mods; if(kc){for(int i=0;i<6;i++)if(kbd.keycode[i]==kc)kbd.keycode[i]=0;} kbdSend(); }

void handleToken(String tok){
  tok = trimCopy(tok); if(tok.length()==0) return;
  if(eqNoCase(tok,"REBOOT_BOOTSEL")){ delay(50); rp2040.rebootToBootloader(); return; }
  if(eqNoCase(tok,"REBOOT") || eqNoCase(tok,"REBOOT_SOFT")){ delay(50); rp2040.reboot(); return; }

  if(tok.startsWith("DELTA:") || tok.startsWith("delta:")){
    float dx=0,dy=0; int c=tok.indexOf(',',6);
    if(c>0){ dx=tok.substring(6,c).toFloat(); dy=tok.substring(c+1).toFloat();
      int sdx=(int)(dx*20.0f); sdx=sdx>127?127:sdx<-128?-128:sdx;
      int sdy=(int)(dy*20.0f); sdy=sdy>127?127:sdy<-128?-128:sdy;
      processMouseDelta(sdx, sdy);
    } return;
  }
  if(tok.startsWith("WAIT_") || tok.startsWith("wait_")){ int ms=tok.substring(5).toInt(); if(ms<0)ms=0; if(ms>1000)ms=1000; delay(ms); return; }
  if(eqNoCase(tok,"MOUSE_LEFT_DOWN"))  { mousePress(0x01); return; }
  if(eqNoCase(tok,"MOUSE_LEFT_UP"))    { mouseRelease(0x01); return; }
  if(eqNoCase(tok,"MOUSE_RIGHT_DOWN")) { mousePress(0x02); return; }
  if(eqNoCase(tok,"MOUSE_RIGHT_UP"))   { mouseRelease(0x02); return; }
  if(eqNoCase(tok,"MOUSE_MIDDLE_DOWN")){ mousePress(0x04); return; }
  if(eqNoCase(tok,"MOUSE_MIDDLE_UP"))  { mouseRelease(0x04); return; }
  if(tok.startsWith("KEY_DOWN:") || tok.startsWith("key_down:")){ uint8_t kc=0,m=0; kc=keyForName(tok.substring(9),m); kbdPress(kc,m); return; }
  if(tok.startsWith("KEY_UP:")   || tok.startsWith("key_up:"))  { uint8_t kc=0,m=0; kc=keyForName(tok.substring(7),m); kbdRelease(kc,m); return; }
  { uint8_t kc=0,m=0; kc=keyForName(tok,m); if(kc||m){ kbdPress(kc,m); delay(10); kbdRelease(kc,m);} }
}
void parseAndExecute(const String& line){
  int start=0; while(true){ int c=line.indexOf(',',start); String tok=(c==-1)?line.substring(start):line.substring(start,c); handleToken(tok); if(c==-1)break; start=c+1; }
}

// ===== CBv0 =====
#define CB_MAGIC 0xCB
#define CB_VERSION 0x01
// CBv0 typed payload kinds (must match Android encoder)
#define TYPE_KEY         0x01
#define TYPE_MOUSE_DELTA 0x02
#define TYPE_MOUSE_BTN   0x03

static uint8_t crc8_poly07(const uint8_t* data, int len){
  uint8_t crc = 0x00;
  for(int i=0;i<len;i++){
    crc ^= data[i];
    for(int b=0;b<8;b++){
      if(crc & 0x80) crc = (uint8_t)((crc<<1) ^ 0x07);
      else           crc <<= 1;
    }
  }
  return crc;
}

static bool parseCbv0AndExecute(const uint8_t* buf, int n){
  if(n < 6){ Serial.print("CBv0 short packet n="); Serial.println(n); return false; }
  if(buf[0] != CB_MAGIC || buf[1] != CB_VERSION){ Serial.print("CBv0 bad header m="); Serial.print(buf[0], HEX); Serial.print(" v="); Serial.println(buf[1]); return false; }
  // uint16_t seq = (uint16_t)(buf[2] | (buf[3]<<8));
  uint8_t type = buf[4];
  uint8_t crc_calc = crc8_poly07(buf, n-1);
  uint8_t crc_pkt  = buf[n-1];
  if(crc_calc != crc_pkt){
    Serial.println("CBv0 CRC mismatch");
    return true;
  }
  const uint8_t* payload = buf + 5;
  int payload_len = n - 6; // exclude header(5) + crc(1)
  Serial.print("CBv0 type "); Serial.print(type, HEX); Serial.print(" len "); Serial.println(payload_len);

  // First: decode by explicit CBv0 type (preferred)
  switch(type){
    case TYPE_KEY: {
      if(payload_len < 2){ Serial.println("CBv0 KEY short"); return true; }
      uint8_t op   = payload[0];      // 0=down,1=up,2=press
      uint8_t klen = payload[1];
      if(payload_len < 2 + klen){ Serial.println("CBv0 KEY len"); return true; }

      String key; key.reserve(klen);
      for(int i=0;i<klen;i++) key += (char)payload[2+i];

      Serial.print("CBv0 KEY str='"); Serial.print(key); Serial.println("'");

      // Special-case: some senders misclassify gamepad as TYPE_KEY with X360* tokens
      int xgid=-1; uint8_t xop=2;
      if(parseX360Key(key, xgid, xop)){
        Serial.print("CBv0 KEY->X360 map id="); Serial.print(xgid); Serial.print(" op="); Serial.println(xop);
        if(xop == 0)           { gpSetButton(xgid, true); }
        else if(xop == 1)      { gpSetButton(xgid, false); }
        else /*press*/         { gpSetButton(xgid, true); delay(15); gpSetButton(xgid, false); }
        return true;
      }

      // X360 dpad tokens -> hat
      uint8_t hat=8, hop=2;
      if(parseX360Dpad(key, hat, hop)){
        Serial.print("CBv0 KEY->X360 DPAD hat="); Serial.print(hat); Serial.print(" op="); Serial.println(hop);
        if(hop == 0){ gp.hat = hat; gp_dirty = true; }
        else if(hop == 1){ gp.hat = 8; gp_dirty = true; }
        else { gp.hat = hat; gp_dirty = true; delay(15); gp.hat = 8; gp_dirty = true; }
        return true;
      }

      uint8_t mods=0; uint8_t kc = keyForName(key, mods);
      if(kc!=0 || mods!=0){
        if(op == 0)           { kbdPress(kc, mods); }
        else if(op == 1)      { kbdRelease(kc, mods); }
        else /*op==2*/        { kbdPress(kc, mods); delay(10); kbdRelease(kc, mods); }
        return true;
      }

      int gid = gpIdForName(key);
      if(gid >= 0){
        Serial.print("CBv0 KEY->GP_BUTTON id="); Serial.print(gid); Serial.print(" op="); Serial.println(op);
        if(op == 0)           { gpSetButton(gid, true); }
        else if(op == 1)      { gpSetButton(gid, false); }
        else /*press*/        { gpSetButton(gid, true); delay(15); gpSetButton(gid, false); }
        return true;
      }

      Serial.println("CBv0 KEY unknown mapping");
      return true;
    }

    case TYPE_MOUSE_DELTA: {
      if(payload_len == 2){
        // Treat as alternate trigger format: [op, which] (0=LT,1=RT)
        uint8_t op = payload[0];
        uint8_t which = payload[1] & 0x01;
        int btnId = (which==0) ? 10 : 11;
        Serial.print("CBv0 ALT_TRIGGER which="); Serial.print(which); Serial.print(" op="); Serial.println(op);
        if(op == 0)           { gpSetButton(btnId, true); }
        else if(op == 1)      { gpSetButton(btnId, false); }
        else /*press*/        { gpSetButton(btnId, true); delay(15); gpSetButton(btnId, false); }
        return true;
      }
      if(payload_len != 4){ Serial.print("CBv0 MOUSE_DELTA bad len "); Serial.println(payload_len); return true; }
      int16_t dx = (int16_t)(payload[0] | (payload[1]<<8));
      int16_t dy = (int16_t)(payload[2] | (payload[3]<<8));
      if(dx > 127) dx = 127; if(dx < -128) dx = -128;
      if(dy > 127) dy = 127; if(dy < -128) dy = -128;
      processMouseDelta(dx, dy);
      return true;
    }

    case TYPE_MOUSE_BTN: {
      if(payload_len < 2) return true;
      uint8_t op = payload[0];       // 0=down,1=up,2=click
      uint8_t id = payload[1];       // 1=left,2=right,3=middle
      uint8_t mask = (id==1?0x01:(id==2?0x02:(id==3?0x04:0)));
      if(mask==0) return true;
      if(op == 0)           { mousePress(mask); }
      else if(op == 1)      { mouseRelease(mask); }
      else /*click*/        { mousePress(mask); delay(5); mouseRelease(mask); }
      return true;
    }

    // Gamepad: buttons, sticks, triggers (CBv0)
    case 0x04: { // TYPE_GP_BUTTON
      if(payload_len < 2){ Serial.print("CBv0 GP_BUTTON short len "); Serial.println(payload_len); return true; }
      uint8_t op = payload[0]; // 0=down/hold,1=up,2=press
      uint8_t id = payload[1]; // 0=A,1=B,2=X,3=Y,4=LB,5=RB,6=BACK,7=START,8=LSC,9=RSC
      // Debug: confirm CBv0 gamepad button decode
      Serial.print("CBv0 GP_BUTTON id="); Serial.print(id);
      Serial.print(" op="); Serial.println(op);
      if(id > 15){ Serial.print("CBv0 GP_BUTTON id out of range: "); Serial.println(id); return true; }
      // Map dpad ids (12..15) to hat; keep others as buttons
      if(id >= 12 && id <= 15){
        uint8_t hat = 8;
        if(id == 12) hat = 0;      // UP
        else if(id == 13) hat = 4; // DOWN
        else if(id == 14) hat = 6; // LEFT
        else if(id == 15) hat = 2; // RIGHT
        if(op == 0){ gp.hat = hat; gp_dirty = true; }
        else if(op == 1){ gp.hat = 8; gp_dirty = true; }
        else { gp.hat = hat; gp_dirty = true; delay(15); gp.hat = 8; gp_dirty = true; }
      } else {
        if(op == 0)           { gpSetButton(id, true); }
        else if(op == 1)      { gpSetButton(id, false); }
        else /*press*/        { gpSetButton(id, true); delay(15); gpSetButton(id, false); }
      }
      return true;
    }

    case 0x05: { // TYPE_GP_STICK
      if(payload_len < 5){ Serial.print("CBv0 GP_STICK short len "); Serial.println(payload_len); return true; }
      uint8_t which = payload[0]; // 0=LS,1=RS
      int16_t xi = (int16_t)(payload[1] | (payload[2]<<8));
      int16_t yi = (int16_t)(payload[3] | (payload[4]<<8));
      int8_t x8 = scale_i16_to_i8(xi);
      int8_t y8 = scale_i16_to_i8(yi);
      // small deadzone
      if(x8>-3 && x8<3) x8=0; if(y8>-3 && y8<3) y8=0;
      gpSetStickDeferred(which, x8, y8);
      Serial.print("CBv0 GP_STICK which="); Serial.print(which); Serial.print(" x="); Serial.print(x8); Serial.print(" y="); Serial.println(y8);
      return true;
    }

    case 0x06: { // TYPE_GP_TRIGGER → no analog axis in our simple report; map to buttons 10/11
      if(payload_len < 2){ Serial.print("CBv0 GP_TRIGGER short len "); Serial.println(payload_len); return true; }
      uint8_t which = payload[0]; // 0=LT,1=RT
      uint8_t v     = payload[1]; // 0..255
      bool down = v >= 128;       // threshold
      int btnId = (which==0) ? 10 : 11; // map triggers to buttons 10/11
      gpSetButton(btnId, down);
      return true;
    }

    default: break; // fall through to heuristics
  }

  // Heuristic handlers (back-compat):
  // 1) If payload looks printable ASCII, treat as compat string
  bool printable=true;
  for(int i=0;i<payload_len;i++){ uint8_t c=payload[i]; if(!(c==9 || c==10 || c==13 || (c>=32 && c<=126))) { printable=false; break; } }
  if(printable){
    String s; s.reserve(payload_len);
    for(int i=0;i<payload_len;i++) s += (char)payload[i];
    Serial.print("CBv0 compat(ascii): "); Serial.println(s);
    parseAndExecute(s);
    return true;
  }

  // 2) If payload is exactly 4 bytes => interpret as mouse delta (int16_le dx,dy)
  if(payload_len == 4){
    int16_t dx = (int16_t)(payload[0] | (payload[1]<<8));
    int16_t dy = (int16_t)(payload[2] | (payload[3]<<8));
    // clamp to HID report range
    if(dx > 127) dx = 127; if(dx < -128) dx = -128;
    if(dy > 127) dy = 127; if(dy < -128) dy = -128;
    processMouseDelta(dx, dy);
    Serial.print("CBv0 mouse dx="); Serial.print(dx); Serial.print(" dy="); Serial.println(dy);
    return true;
  }

  // 3) If payload is 1 byte, treat as mouse button mask (bit0=L, bit1=R, bit2=M)
  if(payload_len == 1){
    uint8_t m = payload[0] & 0x07;
    mouse_buttons = m;
    mouseSend(0,0);
    Serial.print("CBv0 mouse btn="); Serial.println(m, DEC);
    return true;
  }

  // 4) Otherwise ignore for now
  Serial.print("CBv0 type "); Serial.print(type); Serial.print(" len "); Serial.println(payload_len);
  return true;
}

// ===== HTTP =====
void httpTask(){
  WiFiClient client = http.accept();
  if(!client) return;
  char line[128]; int n=0; unsigned long t0=millis();
  while((millis()-t0)<500 && client.connected()){
    if(client.available()){
      char ch = client.read(); if(ch=='\r') continue; if(ch=='\n') break; if(n<127) line[n++]=ch;
    }
  }
  line[n]=0;
  bool doBootsel=false, doReboot=false, ok=false;
  if(n>0){ if(strncmp(line,"GET /reboot_bootsel",19)==0){ doBootsel=true; ok=true; } else if(strncmp(line,"GET /reboot",11)==0){ doReboot=true; ok=true; } else ok=true; }
  if(ok){
    client.println("HTTP/1.1 200 OK"); client.println("Content-Type: text/html"); client.println("Connection: close"); client.println();
    client.println("<!doctype html><html><body><h3>ConsoleBridge Pico</h3><p><a href=\"/reboot_bootsel\">Reboot to BOOTSEL</a></p><p><a href=\"/reboot\">Soft Reboot</a></p></body></html>");
  } else {
    client.println("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n");
  }
  client.flush(); delay(10); client.stop();
  if(doBootsel){ delay(50); rp2040.rebootToBootloader(); }
  if(doReboot){ delay(50); rp2040.reboot(); }
}

// ===== setup/loop =====
void setup(){
  Serial.begin(115200); delay(50);
#if GAMEPAD_ONLY
  usb_hid_gp.begin();
#else
  usb_hid_km.begin();
  usb_hid_gp.begin();
#endif
  waitHIDReady();
  memset(&kbd,0,sizeof(kbd));
#if !GAMEPAD_ONLY
  kbdSend();
  mouseSend(0,0);
#endif
  gpSend();
  setPattern(LED_WIFI_CONNECTING);

  WiFi.mode(WIFI_STA);
  WiFi.config(ip, gateway, subnet);
  WiFi.begin(STA_SSID, STA_PASS);
  unsigned long t0=millis();
  while(WiFi.status()!=WL_CONNECTED && millis()-t0<10000){ ledTask(); delay(10); }
  if(WiFi.status()==WL_CONNECTED){ setPattern(LED_STA); Serial.print("Pico STA IP: "); Serial.println(WiFi.localIP()); }
  else { WiFi.mode(WIFI_AP); WiFi.softAP(AP_SSID, AP_PASS); setPattern(LED_AP); Serial.print("Pico AP IP:  "); Serial.println(WiFi.softAPIP()); }
  udp.begin(CB_PORT);
  http.begin();
}

void loop(){
  ledTask();
  int nbytes = udp.parsePacket();
  if(nbytes>0){
    uint8_t buf[1024]; int n=udp.read(buf, sizeof(buf));
    if(n>0){
      bool wasCb = parseCbv0AndExecute(buf, n);
      if(!wasCb){ buf[n]=0; Serial.print("UDP txt: "); Serial.println((char*)buf); parseAndExecute(String((char*)buf)); }
      blinkActivity(5);
    }
  }
  httpTask();
  activityLedService();
  hidTick();
}
