MAINTENANCE MANAGER - WINDOWS INSTALL
=====================================

1. Make sure this folder was EXTRACTED (right-click the zip > Extract All...).
2. Double-click "Install Maintenance Manager".
   - If Windows says "Windows protected your PC": click "More info", then "Run anyway".
   - Click "Yes" when Windows asks for permission (needed once, to set up the database).
3. Wait for "Installation complete!". The app opens by itself.

After that, open it with the "Maintenance Manager" icon on your Desktop or in the Start menu.
First login (new install only): admin / admin123 - you will be asked to choose a new password.

The installer adds Java 21 and PostgreSQL automatically if they are missing (internet needed).
Running the installer again upgrades the app and keeps all your data.

Stop the server:   Start menu > Stop Maintenance Manager
Settings:          %LOCALAPPDATA%\MaintenanceManager\config.ps1
Logs:              %LOCALAPPDATA%\MaintenanceManager\logs
