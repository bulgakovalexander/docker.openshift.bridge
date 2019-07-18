#!/usr/bin/env bash


docker run -v $(pwd)/:/workspace/ gcr.io/kaniko-project/executor:latest --destination MacBook-Pro-Alexander.local:5000/chaincode-test --dockerfile /wokrspace/Dockerfile