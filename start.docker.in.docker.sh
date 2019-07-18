#!/usr/bin/env bash
docker run --privileged --name docker -d --rm -p 2375:2375 docker:dind

