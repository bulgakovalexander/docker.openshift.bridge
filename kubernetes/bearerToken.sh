#!/usr/bin/env bash

kubectl apply -f admin-user.yml
kubectl apply -f cluster-admin-role.yml
kubectl -n kube-system describe secret $(kubectl -n kube-system get secret | grep admin-user | awk '{print $1}')