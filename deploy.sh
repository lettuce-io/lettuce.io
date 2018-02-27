#!/bin/bash

PORT1=${SERVER_PORT1:-9081}
PORT2=${SERVER_PORT2:-9082}

touch .deploying
function checkRun {
    nc -w 1 localhost $1 </dev/null
    echo -n $?
}

function startApplication {

    echo "[INFO] Starting application on port $1"

    export SERVER_PORT=$1
    rm -f lettuce-home-all-$1.jar
    cp lettuce-home-all.jar lettuce-home-all-$1.jar
    ./start.sh

    for start in {1..20}
    do
        nc  -w 1 localhost $1 </dev/null
        if [[ $? == 0 ]] ; then
            echo "[INFO] Application is up and running"
            rm .deploying
            RC=0
            return 0
        fi
        sleep 1
    done

    echo "[ERROR] Cannot start application"
    RC=1
    return 1
}

function killInstance {

    PIDFILE=lettuce-home-$1.pid
    if [[ -f ${PIDFILE} ]] ; then
        APP_PID=$(cat ${PIDFILE})
        echo "[INFO] Attempt to kill running application with pid $APP_PID"
        kill ${APP_PID}

        if [[ $? == 0 ]] ; then
            rm ${PIDFILE}
        fi
    fi

    rm -f lettuce-home-all-$1.jar
}

PORT1_RUNNING=$(checkRun ${PORT1})
PORT2_RUNNING=$(checkRun ${PORT2})

if [[ "${PORT1_RUNNING}" = "0" ]] ; then

    killInstance ${PORT2}
    startApplication ${PORT2}

    if [[ ${RC} == 0 ]] ; then
        killInstance ${PORT1}
    fi
    exit ${RC}
else

    killInstance ${PORT1}
    startApplication ${PORT1}

    if [[ ${RC} == 0 ]] ; then
        killInstance ${PORT2}
    fi
    exit ${RC}
fi
