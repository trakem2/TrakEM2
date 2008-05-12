uname_O := $(shell sh -c 'uname -o 2>/dev/null || echo not')
ifeq ($(uname_O),Cygwin)
CPSEP=\;
else
CPSEP=:
endif

PLUGINSDIR ?= ../plugins
JARSDIR ?= ../jars
EXTJARS=$(wildcard $(PLUGINSDIR)/*.jar) $(wildcard $(JARSDIR)/*.jar)
JAVACOPTSJARS=$(shell echo "$(EXTJARS)" | tr \  $(CPSEP))
JAVACOPTS=-classpath ../ij.jar$(CPSEP)$(JAVACOPTSJARS) -target 1.5 -source 1.5

JAVAS=$(wildcard ini/*/*.java ini/*/*/*.java mpi/*/*/*.java  mpi/*/*/*/*.java)
CLASSES=$(patsubst %.java,%.class,$(JAVAS))
ALL_CLASSES=$(patsubst %.java,%*.class,$(JAVAS))
TARGET=TrakEM2_.jar

# does not work yet...
SIFT_JAVAS=$(wildcard *.java)
SIFT_CLASSES=$(patsubst %.java,%.class,$(SIFT_JAVAS))
SIFT_TARGET=SIFT_Matcher_new.jar

all: $(TARGET)

show:
	echo $(ALL_CLASSES)

$(TARGET): plugins.config $(CLASSES)
	jar cvf $@ $< $(ALL_CLASSES)

$(CLASSES): %.class: %.java
	javac -O $(JAVACOPTS) $(JAVAS)

$(SIFT_TARGET): $(SIFT_CLASSES)
	jar cvf $@ $^

$(SIFT_CLASSES): %.class: %.java
	javac -O $(JAVACOPTS) $(SIFT_JAVAS)


