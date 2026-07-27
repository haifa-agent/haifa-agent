set -eu
rm -rf .verify-out
mkdir -p .verify-out
javac --release 21 -d .verify-out \
  src/main/java/sample/RetryPolicy.java \
  src/test/java/sample/RetryPolicyTest.java
java -cp .verify-out sample.RetryPolicyTest
