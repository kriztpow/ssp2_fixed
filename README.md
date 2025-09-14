# ScreenShare Project Final

This project is a minimal Android app to capture and stream the device screen over LAN via MJPEG.

**Important:** The app requires explicit user consent for screen capture and will show a foreground notification while sharing.

## How to use
1. Unzip and push this project to your GitHub repo.
2. After push, go to Actions -> Android CI and open the latest run.
3. Download the artifact `app-debug.apk`.
4. Install on your Android 11 device and press "Compartir".
5. From another device on the same Wi‑Fi open: http://<IP_OF_YOUR_PHONE>:8080/

## Notes
- To run workflows, this repo uses GitHub Actions that install Gradle on the runner.
- For production or better performance consider WebRTC or H.264 encoding.
