set -eu
rm -rf .verify-out
mkdir -p .verify-out
javac --release 21 -d .verify-out \
  src/main/java/sample/UsernameValidator.java \
  src/test/java/sample/UsernameValidatorTest.java
java -cp .verify-out sample.UsernameValidatorTest
