#!/usr/bin/env bash
# Запуск RPC. Discord (или Vesktop) должен быть запущен. status.json лежит рядом.
cd "$(dirname "$0")"
exec "$HOME/.local/jdk8/bin/java" -Djna.library.path=lib -jar MyDiscordRPC.jar
