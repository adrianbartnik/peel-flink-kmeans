#!/usr/bin/Rscript

require(ggplot2)

args = commandArgs(TRUE)

X11()
mydata = read.csv(args[1]) # Or add header = True, for automatic naming of columns
colnames(mydata) <- c("Centroid", "X", "Y")
mydata$Centroid <- as.factor(mydata$Centroid)

evenPlot = !is.na(args[2])
min = min(mydata$X, mydata$Y)
max = max(mydata$X, mydata$Y)

if (evenPlot) {
  qplot(X, Y, data=mydata, colour = Centroid, xlim=c(min, max), ylim=c(min, max))
} else {
  qplot(X, Y, data=mydata, colour = Centroid)
}

message("Press Return To Continue")
invisible(readLines("stdin", n=1))
