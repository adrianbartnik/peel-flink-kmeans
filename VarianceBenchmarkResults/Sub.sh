#!/bin/bash

array=("$@")
end=$(($# - 1))

for i in `seq 1 2 $end`
do
  to=$((i+1))
  from="${array[$i]}"
  until="${array[$to]}"
  sed -i "s/^$from,/$until,/g" $1
done
