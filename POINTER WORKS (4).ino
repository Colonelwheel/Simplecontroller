#include <WiFi.h>
#include <WiFiUdp.h>
#include <WiFiClient.h>
#include <WiFiServer.h>
#include <Adafruit_TinyUSB.h>
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
uint8_t const hid_desc[] = {
  TUD_HID_REPORT_DESC_KEYBOARD(HID_REPORT_ID(1)),
  TUD_HID_REPORT_DESC_MOUSE(HID_REPORT_ID(2)),
  TUD_HID_REPORT_DESC_GAMEPAD(HID_REPORT_ID(3))
};
Adafruit_USBD_HID usb_hid(hid_desc, sizeof(hid_desc), HID_ITF_PROTOCOL_NONE, 3, false);
typedef struct __attribute__((packed)) { uint8_t modifier; uint8_t reserved; uint8_t keycode[6]; } hid_kbd_report_t;
hid_kbd_report_t kbd = {0,0,{0,0,0,0,0,0}};
uint8_t mouse_buttons = 0;
enum { MOD_LCTRL=0x01, MOD_LSHIFT=0x02, MOD_LALT=0x04, MOD_LGUI=0x08 };
static void waitHIDReady(){ uint32_t t0=millis(); while(!TinyUSBDevice.mounted() && (millis()-t0)<5000) delay(10); }
static void blinkActivity(uint16_t ms){ digitalWrite(LED_BUILTIN,HIGH); delay(ms); digitalWrite(LED_BUILTIN,LOW); }
void kbdSend(){ usb_hid.sendReport(1, &kbd, sizeof(kbd)); }
void mouseSend(int8_t dx,int8_t dy){ uint8_t rpt[5]={mouse_buttons,(uint8_t)dx,(uint8_t)dy,0,0}; usb_hid.sendReport(2,rpt,sizeof(rpt)); }
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
  usb_hid.sendReport(3, &gp, sizeof(gp));
}
void gpSetButton(int id, bool down){ if(id<0||id>15) return; uint16_t mask = (1u<<id); if(down) gp.buttons |= mask; else gp.buttons &= ~mask; gpSend(); }

// ===== Legacy ASCII (fallback) =====
static inline String trimCopy(const String& s){ int i=0,j=s.length()-1; while(i<=j&&isspace((unsigned char)s[i])) i++; while(j>=i&&(isspace((unsigned char)s[j])||s[j]==',')) j--; return (j<i)?String(""):s.substring(i,j+1); }
static inline bool eqNoCase(const String& a, const char* b){ String aa=a; aa.toUpperCase(); String bb=b; for(size_t i=0;i<bb.length();++i) bb[i]=toupper(bb[i]); return aa==bb; }

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
      mouseSend((int8_t)sdx,(int8_t)sdy);
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
  if(n < 6) return false;
  if(buf[0] != CB_MAGIC || buf[1] != CB_VERSION) return false;
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

  // First: decode by explicit CBv0 type (preferred)
  switch(type){
    case TYPE_KEY: {
      if(payload_len < 2){ Serial.println("CBv0 KEY short"); return true; }
      uint8_t op   = payload[0];      // 0=down,1=up,2=press
      uint8_t klen = payload[1];
      if(payload_len < 2 + klen){ Serial.println("CBv0 KEY len"); return true; }

      String key; key.reserve(klen);
      for(int i=0;i<klen;i++) key += (char)payload[2+i];

      uint8_t mods=0; uint8_t kc = keyForName(key, mods);
      if(op == 0)           { kbdPress(kc, mods); }
      else if(op == 1)      { kbdRelease(kc, mods); }
      else /*op==2*/        { kbdPress(kc, mods); delay(10); kbdRelease(kc, mods); }
      return true;
    }

    case TYPE_MOUSE_DELTA: {
      if(payload_len != 4) return true;
      int16_t dx = (int16_t)(payload[0] | (payload[1]<<8));
      int16_t dy = (int16_t)(payload[2] | (payload[3]<<8));
      if(dx > 127) dx = 127; if(dx < -128) dx = -128;
      if(dy > 127) dy = 127; if(dy < -128) dy = -128;
      mouseSend((int8_t)dx, (int8_t)dy);
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
      if(payload_len < 2) return true;
      uint8_t op = payload[0]; // 0=down/hold,1=up,2=press
      uint8_t id = payload[1]; // 0=A,1=B,2=X,3=Y,4=LB,5=RB,6=BACK,7=START,8=LSC,9=RSC
      // Debug: confirm CBv0 gamepad button decode
      Serial.print("CBv0 GP_BUTTON id="); Serial.print(id);
      Serial.print(" op="); Serial.println(op);
      if(op == 0)           { gpSetButton(id, true); }
      else if(op == 1)      { gpSetButton(id, false); }
      else /*press*/        { gpSetButton(id, true); delay(15); gpSetButton(id, false); }
      return true;
    }

    case 0x05: { // TYPE_GP_STICK
      if(payload_len < 5) return true;
      uint8_t which = payload[0]; // 0=LS,1=RS
      int16_t xi = (int16_t)(payload[1] | (payload[2]<<8));
      int16_t yi = (int16_t)(payload[3] | (payload[4]<<8));
      int8_t x8 = scale_i16_to_i8(xi);
      int8_t y8 = scale_i16_to_i8(yi);
      // small deadzone
      if(x8>-3 && x8<3) x8=0; if(y8>-3 && y8<3) y8=0;
      if(which == 0){ gp.x = x8; gp.y = y8; }
      else          { gp.z = x8; gp.rz = y8; }
      gpSend();
      return true;
    }

    case 0x06: { // TYPE_GP_TRIGGER → no analog axis in our simple report; map to buttons 10/11
      if(payload_len < 2) return true;
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
    mouseSend((int8_t)dx, (int8_t)dy);
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
  usb_hid.begin(); waitHIDReady(); memset(&kbd,0,sizeof(kbd)); kbdSend(); mouseSend(0,0); gpSend();
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
}
