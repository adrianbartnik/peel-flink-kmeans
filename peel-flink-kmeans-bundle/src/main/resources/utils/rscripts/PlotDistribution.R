#!/usr/bin/Rscript

args = commandArgs(TRUE)

X11()
mydata = read.csv(args[1], header = TRUE)
colors = c("red", "blue", "green", "yellow", "grey", "purple", "black")

plot(mydata$X, mydata$Y, col=colors[mydata$Centroid + 1], xlab="X", ylab="Y")
legend(x="topright", y="YAxis", legend=levels(factor(mydata$Centroid)), col=colors, pch=1)

message("Press Return To Continue")
invisible(readLines("stdin", n=1))
