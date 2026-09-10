/*
  بديل اختبار Serial Monitor - بيبعت المسافة عبر الواي فاي عشان التطبيق يقدر يقراها لحظيًا.

  التوصيل الفيزيائي الصحيح (بالأرقام المطبوعة على لوحة NodeMCU-32S):
    VL53L1X VCC  -> 3.3V
    VL53L1X GND  -> GND
    VL53L1X SDA  -> الرِجل المطبوع عليها "42" (وده فعليًا GPIO21 الحقيقي)
    VL53L1X SCL  -> الرِجل المطبوع عليها "39" (وده فعليًا GPIO22 الحقيقي)

  المكتبات المطلوبة: "VL53L1X" بتاعة Pololu + WebServer (جاهزة مع ESP32)
*/

#include <WiFi.h>
#include <WebServer.h>
#include <Wire.h>
#include <VL53L1X.h>

// ===========================
// نفس شبكة الواي فاي اللي الكاميرا متوصلة بيها بالظبط
// ===========================
const char* ssid = "mikky";
const char* password = "Sama1711#";

VL53L1X sensor;
WebServer server(80);

volatile int lastDistanceMm = -1;

void handleDistance() {
  server.send(200, "text/plain", String(lastDistanceMm));
}

void setup() {
  Serial.begin(115200);

  // بنحدد أرجل GPIO الحقيقية صراحة (21, 22) عشان نضمن مفيش لخبطة
  Wire.begin(21, 22);
  Wire.setClock(400000);

  sensor.setTimeout(500);
  if (!sensor.init()) {
    Serial.println("فشل في الاتصال بالحساس! تأكد من التوصيلات.");
    while (1) { delay(1000); }
  }

  sensor.setDistanceMode(VL53L1X::Long);
  sensor.setMeasurementTimingBudget(50000);
  sensor.startContinuous(50);

  Serial.println("الحساس شغال، بنتصل بالواي فاي...");

  WiFi.mode(WIFI_STA);
  WiFi.begin(ssid, password);
  Serial.print("Connecting to WiFi");
  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }
  Serial.println();
  Serial.print("WiFi connected! IP address: ");
  Serial.println(WiFi.localIP());

  server.on("/distance", handleDistance);
  server.begin();
  Serial.println("HTTP server started - use /distance to read");
}

void loop() {
  int d = sensor.read();
  if (!sensor.timeoutOccurred()) {
    lastDistanceMm = d;
  }
  server.handleClient();
}
