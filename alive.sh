#!/bin/bash

PORT=$1
RUN_PATH=/srv/vhosts/l/lettuce.io/live

if [ -f "${RUN_PATH}/.deploying" ] ; then
    echo "Deploy in progress...exiting"
    exit 0
fi

curl -s http://localhost:${PORT} > /dev/null
RC=$?

if [[ ${RC} == 0 ]] ; then
  echo "Success"
  exit 0
else
  cd ${RUN_PATH}
  rm -f lettuce-home-all-${PORT}.jar
  cp lettuce-home-all.jar lettuce-home-all-${PORT}.jar
  SERVER_PORT=${PORT} ${RUN_PATH}/start.sh
  exit $?
fi
