#!/bin/bash

PORT=${SERVER_PORT:-9080}

rm -f lettuce-home-${PORT}.log
nohup java -Dserver.port=${PORT} -jar lettuce-home-all-${PORT}.jar > lettuce-home-${PORT}.log 2>&1 &

RC=$?
if [[ $RC == 0 ]] ; then
  echo $! > lettuce-home-${PORT}.pid
else
  echo "Error $RC during startup"
  cat lettuce-home-${PORT}.log
fi
