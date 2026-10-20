#!/bin/bash
cd /data/data/com.termux/files/home/LC
exec ./gradlew :app:assembleDebug -x test "$@"
