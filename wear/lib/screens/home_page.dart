import 'package:flutter/material.dart';
import '../widgets/widgets.dart';

class MyHomePage extends StatefulWidget {
  const MyHomePage({super.key, required this.title});

  final String title;

  @override
  State<MyHomePage> createState() => _MyHomePageState();
}

class _MyHomePageState extends State<MyHomePage> {
  // Shared state for the timer duration
  int _currentDuration = 20;

  void _handleTimerChanged(int newDuration) {
    setState(() {
      _currentDuration = newDuration;
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            TimerDisplay(
              initialValue: _currentDuration,
              onTimerChanged: _handleTimerChanged,
            ),
            const SizedBox(height: 16),
            FocusButton(duration: _currentDuration),
          ],
        ),
      ),
    );
  }
}
