import 'package:flutter/material.dart';

// Create a callback type for when the timer changes
typedef TimerChangeCallback = void Function(int duration);

class TimerDisplay extends StatefulWidget {
  final int initialValue;
  final TimerChangeCallback? onTimerChanged;
  
  const TimerDisplay({
    super.key, 
    this.initialValue = 20,
    this.onTimerChanged,
  });

  @override
  State<TimerDisplay> createState() => TimerDisplayState();
}

// Make the state public so it can be accessed
class TimerDisplayState extends State<TimerDisplay> {
  late int _timeRemaining;
  
  @override
  void initState() {
    super.initState();
    _timeRemaining = widget.initialValue;
  }

  // Getter to expose the time remaining to other widgets
  int get timeRemaining => _timeRemaining;

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        Container(
          decoration: BoxDecoration(
            border: Border.all(color: Colors.grey.shade700, width: 1),
            borderRadius: BorderRadius.circular(8),
          ),
          child: IconButton(
            icon: const Icon(Icons.remove, size: 16),
            onPressed: () {
              setState(() {
                if (_timeRemaining > 5) {
                  _timeRemaining -= 5;
                } else {
                  _timeRemaining = 0;
                }
                // Notify listeners when the timer changes
                widget.onTimerChanged?.call(_timeRemaining);
              });
            },
            padding: const EdgeInsets.all(4),
            constraints: const BoxConstraints(
              minWidth: 24,
              minHeight: 24,
              maxWidth: 28,
              maxHeight: 28,
            ),
            iconSize: 16,
          ),
        ),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 8),
          child: Text(
            "$_timeRemaining",
            style: const TextStyle(fontSize: 48, fontWeight: FontWeight.bold),
          ),
        ),
        Container(
          decoration: BoxDecoration(
            border: Border.all(color: Colors.grey.shade700, width: 1),
            borderRadius: BorderRadius.circular(8),
          ),
          child: IconButton(
            icon: const Icon(Icons.add, size: 16),
            onPressed: () {
              setState(() {
                if (_timeRemaining <= 55) {
                  _timeRemaining += 5;
                } else {
                  _timeRemaining = 60;
                }
                // Notify listeners when the timer changes
                widget.onTimerChanged?.call(_timeRemaining);
              });
            },
            padding: const EdgeInsets.all(4),
            constraints: const BoxConstraints(
              minWidth: 24,
              minHeight: 24,
              maxWidth: 28,
              maxHeight: 28,
            ),
            iconSize: 16,
          ),
        ),
      ],
    );
  }
}
