A simple `watch` command line tool for watching a specific directory and 
(re)-executing a specific command on every change, with possible throttling. ⚡

Using [Scala-CLI](https://scala-cli.virtuslab.org/) as build tool. ✅

Based on the Scala  [typelevel](https://typelevel.org/) stack. ✅

If you have [nix](https://nixos.org/download.html) installed and [flakes enabled](https://nixos.wiki/wiki/Flakes#Enable_flakes):

```shell
nix run github:ramytanios/watch#jvm --refresh
```

For developing, there is a corresponding dev shell:

```shell
nix develop
```

# Motivation
[Smithy](https://smithy.io/2.0/index.html) is a a great Interface Definition Language. [Smithy4s](https://disneystreaming.github.io/smithy4s/) is a codegen for generating Scala code based on Smithy files.
It is certainly tedious to manually regenerate the Scala code on each smithy file change.

👉Watching the `.smithy` files and regenerate the corresponding Scala code programatically is certainly more idiomatic.

