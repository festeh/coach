import 'package:flutter/material.dart';
import 'widgets/widgets.dart';

import 'package:flutter/material.dart';
import 'widgets/widgets.dart';
import 'screens/home_page.dart';

void main() {
  // Check for COACH_ADDR environment variable at startup
  final serverAddress = const String.fromEnvironment('COACH_ADDR');
  if (serverAddress.isEmpty) {
    throw Exception(
      'COACH_ADDR environment variable is not set. '
      'Build with --dart-define=COACH_ADDR=https://your-server.com',
    );
  }

  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Coach',
      theme: ThemeData.dark().copyWith(
        colorScheme: ColorScheme.dark(
          primary: Colors.deepPurple.shade300,
          secondary: Colors.deepPurple.shade200,
        ),
        elevatedButtonTheme: ElevatedButtonThemeData(
          style: ElevatedButton.styleFrom(
            backgroundColor: Colors.deepPurple.shade700,
            foregroundColor: Colors.white,
          ),
        ),
      ),
      home: const MyHomePage(title: 'Coach'),
    );
  }
}
