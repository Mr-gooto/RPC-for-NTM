@echo off
rem NTM RPC launcher for Windows. Discord (or Vesktop) must be running.
cd /d "%~dp0"
java -Djna.library.path=lib -jar MyDiscordRPC.jar
