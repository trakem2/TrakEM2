uname_O := $(shell sh -c 'uname -o 2>/dev/null || echo not')
ifeq ($(uname_O),Cygwin)
CPSEP=\;
else
CPSEP=:
endif

PLUGINSDIR ?= ../plugins
EXTJARS=$(wildcard $(PLUGINSDIR)/*.jar)
JAVACOPTSJARS=$(shell echo "$(EXTJARS)" | tr \  $(CPSEP))
JAVACOPTS=-classpath ../ij.jar$(CPSEP)$(JAVACOPTSJARS) -target 1.5 -source 1.5

JAVAS=$(wildcard ini/*/*.java ini/*/*/*.java mpi/*/*/*.java  mpi/*/*/*/*.java)
CLASSES=$(patsubst %.java,%.class,$(JAVAS))
TARGET=TrakEM2_.jar

# does not work yet...
SIFT_JAVAS=$(wildcard *.java)
SIFT_CLASSES=$(patsubst %.java,%.class,$(SIFT_JAVAS))
SIFT_TARGET=SIFT_Matcher_new.jar

all: $(TARGET)

show:
	echo $(JAVACOPTSJARS)

$(TARGET): plugins.config $(CLASSES)
	jar cvf $@ $^

$(CLASSES): %.class: %.java
	javac -O $(JAVACOPTS) $(JAVAS)

$(SIFT_TARGET): $(SIFT_CLASSES)
	jar cvf $@ $^

$(SIFT_CLASSES): %.class: %.java
	javac -O $(JAVACOPTS) $(SIFT_JAVAS)


