# watch-and-execute
A simple command line tool for watching a specific directory and 
(re)-executing a specific command on every change, with possible throttling. ⚡

# Motivation:
[Smithy](https://smithy.io/2.0/index.html) is a a great Interface Definition Language. [Smithy4s](https://disneystreaming.github.io/smithy4s/) is a codegen for generating Scala code based on Smithy files.
It is certainly tedious to manually regenerate the Scala code on each smithy file change.

👉Watching the `.smihty` files and regenerate the corresponding Scala code programatically is certainly more idiomatic.




