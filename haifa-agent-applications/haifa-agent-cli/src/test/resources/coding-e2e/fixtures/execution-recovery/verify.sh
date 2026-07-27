set -eu
rm -rf .verify-out
mkdir -p .verify-out
javac --release 21 -d .verify-out \
  src/main/java/sample/Range.java \
  src/test/java/sample/RangeTest.java
java -cp .verify-out sample.RangeTest
