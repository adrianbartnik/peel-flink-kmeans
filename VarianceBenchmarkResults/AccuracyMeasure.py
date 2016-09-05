#!/usr/bin/env python

import csv, sys

mapping = {}

totalTruth, totalTesting, hit, miss, errors = (0, 0, 0, 0, 0)

with open(sys.argv[1], 'rb') as groundtruth:
    reader = csv.reader(groundtruth)
    for row in reader:
        totalTruth += 1
        mapping[(row[1], row[2])] = row[0]


with open(sys.argv[2], 'rb') as testing:
    reader = csv.reader(testing)
    for row in reader:
        totalTesting += 1
        try:
            if (mapping[(row[1], row[2])] == row[0]):
                hit += 1
            else:
                miss += 1
        except KeyError:
            errors += 1

print "Total size: ", totalTruth, " and testing size: ", totalTesting
print "Correct assignments: ", hit, " and failed assigments: ", miss
print "Errors: ", errors
print "Accuracy: ", float(hit) / float(totalTruth)
