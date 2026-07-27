set -eu
rm -rf .verify-out
mkdir -p .verify-out
javac --release 21 -d .verify-out src/main/java/sample/Clamp.java src/test/java/sample/ClampTest.java
java -cp .verify-out sample.ClampTest
