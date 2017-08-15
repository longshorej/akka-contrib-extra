#!/usr/bin/env bash

pid=$$

trap 'kill -9 $pid' SIGTERM

printf "Starting"

1>&2 sleep 8 &
wait
