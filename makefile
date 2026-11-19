args ?=
JAVA ?= java
JAVAC ?= javac
mode ?= linux

build:
ifeq ($(mode),linux)
	$(JAVAC) -d ./bin -cp "./bin:./libs" ./src/*.java
else ifeq ($(mode),windows)
	$(JAVAC) -d ./bin -cp "./bin;./libs" ./src/*.java
else
	@echo "Mode not found"
endif
run:
ifeq ($(mode),linux)
	$(JAVA) -cp "./bin:./libs" Main
else ifeq ($(mode),windows)
	$(JAVA) -cp "./bin;./libs" Main
else
	@echo "Mode not found"
endif