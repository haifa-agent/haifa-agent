set -eu
rm -rf .verify-out
mkdir -p .verify-out
javac --release 21 -d .verify-out \
  src/main/java/sample/DiscountPolicy.java \
  src/main/java/sample/OrderTotal.java \
  src/main/java/sample/ThresholdDiscountPolicy.java \
  src/test/java/sample/OrderTotalTest.java
java -cp .verify-out sample.OrderTotalTest
