import 'dart:convert';
import 'dart:ui';

class TodayNotificationData {
  final int day;
  final String solar, hijri, gregorian;
  final Color titleColor, subtitleColor;

  TodayNotificationData(
      {required this.day,
      required this.solar,
      required this.hijri,
      required this.gregorian,
      this.titleColor = const Color(0xFF448AFF),
      this.subtitleColor = const Color(0xFF009688)});

  Map<String, dynamic> toJson() {
    return {
      "day": day,
      "solar": solar,
      "hijri": hijri,
      "gregorian": gregorian,
      "titleColor": '#${titleColor.value.toRadixString(16)}',
      "subtitleColor": '#${subtitleColor.value.toRadixString(16)}',
    };
  }

  String toJsonStr() => json.encode(toJson());
}
