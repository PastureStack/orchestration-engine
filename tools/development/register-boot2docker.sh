#!/bin/bash
set -e -x

DEV_HOST=${DEV_HOST:-10.0.2.2:8080}
AGENT_IMAGE=${AGENT_IMAGE:-ghcr.io/pasturestack/node-agent:v1.2.30@sha256:5310b748fc52bcd87fdeaa2285f424a07ec13c9b41639692eef96bda53ac8277}

if [ -x "$(which boot2docker)" ]; then
    DOCKER_ARGS=${DOCKER_ARGS:--e CATTLE_AGENT_IP=$(boot2docker ip)}
fi

# This is just here to make sure your environment is sane
docker info

CONSOLE_ARGS=""
if [ -t 1 ]; then
    CONSOLE_ARGS="-it"
fi

HOST=${1:-http://${DEV_HOST}}
docker run -e CATTLE_SCRIPT_DEBUG $DOCKER_ARGS --rm $CONSOLE_ARGS -v /var/run/docker.sock:/var/run/docker.sock $AGENT_IMAGE $HOST
