#!/bin/bash

if [ ! -d "$1" ]; then
  echo "Directory $1 does not exist"
  exit -1
fi

for directory in "$1/"*
do
  echo "Merging files in $directory"
  index="${directory: -1}"
  cat $directory/* > $1/output-$index\.csv
  rm -rf $directory
done
