#!/usr/bin/env bash
docker rm -f registry && docker run -d -p 5000:5000 --restart=always --name registry registry:2