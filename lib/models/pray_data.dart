import 'dart:convert';

class PrayNotificationData {
  final List<String> titles;
  final List<String> times;

  PrayNotificationData({required this.titles, required this.times});

  Map<String, dynamic> toJson() {
    return {
      "titles": titles.join(','),
      "times": times.join(','),
    };
  }

  String toJsonStr() => json.encode(toJson());
}
