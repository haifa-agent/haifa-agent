set -eu
rm -rf .verify-out
mkdir -p .verify-out
javac --release 21 -d .verify-out \
  src/main/java/sample/MoneyFormatter.java \
  src/main/java/sample/ReceiptFormatter.java \
  src/test/java/sample/ReceiptFormatterTest.java
java -cp .verify-out sample.ReceiptFormatterTest
