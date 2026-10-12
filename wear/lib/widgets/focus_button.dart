import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;

class FocusButton extends StatefulWidget {
  final int duration;
  
  const FocusButton({
    super.key,
    required this.duration,
  });

  @override
  State<FocusButton> createState() => _FocusButtonState();
}

class _FocusButtonState extends State<FocusButton> {
  bool _isFocused = false;
  int _timeLeftSeconds = 0;
  Timer? _countdownTimer;

  Future<void> _sendFocusRequest(int duration) async {
    try {
      final serverAddress = const String.fromEnvironment('COACH_ADDR');
      final uri = Uri.parse(serverAddress);
      final requestUri = uri.replace(
        queryParameters: {
          ...uri.queryParameters, // Preserve any existing query parameters
          'duration': (duration * 60).toString(),
          'focusing': 'true',
        },
      );
      final response = await http.post(requestUri);
      if (response.statusCode == 200) {
        debugPrint('Request successful: ${response.body}');
        final data = jsonDecode(response.body);
        final focusing = data['focusing'] ?? false;
        final timeLeft = data['focus_time_left'] ?? 0;

        setState(() {
          _isFocused = focusing;
          _timeLeftSeconds = timeLeft;
        });

        _startCountdown();
      } else {
        debugPrint('Request failed with status: ${response.statusCode}');
      }
    } catch (e) {
      debugPrint('Error sending request: $e');
    }
  }

  void _startCountdown() {
    _countdownTimer?.cancel();
    _countdownTimer = Timer.periodic(const Duration(seconds: 1), (_) {
      if (_timeLeftSeconds > 0) {
        setState(() => _timeLeftSeconds--);
      } else {
        _countdownTimer?.cancel();
        setState(() => _isFocused = false);
      }
    });
  }

  String _formatTime(int seconds) {
    final h = seconds ~/ 3600;
    final m = (seconds % 3600) ~/ 60;
    final s = seconds % 60;
    if (h > 0) {
      return '$h:${m.toString().padLeft(2, '0')}:${s.toString().padLeft(2, '0')}';
    }
    return '${m.toString().padLeft(2, '0')}:${s.toString().padLeft(2, '0')}';
  }

  @override
  void dispose() {
    _countdownTimer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return ElevatedButton(
      onPressed: () => _sendFocusRequest(widget.duration),
      style: ElevatedButton.styleFrom(
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
        padding: const EdgeInsets.all(16),
      ),
      child: Text(
        _isFocused ? _formatTime(_timeLeftSeconds) : "Focus",
        style: const TextStyle(fontSize: 18),
      ),
    );
  }
}
