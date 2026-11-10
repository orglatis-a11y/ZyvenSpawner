# ZyvenSpawner
Income spawners inspired by **DonutSMP** (credit to DonutSMP - unofficial fan recreation, not affiliated).

Build: `mvn clean package` -> `target/ZyvenSpawner-1.0.0.jar` -> drop into `plugins/` (Paper 1.20.4+, Java 17, Vault optional for selling).

- Silk Touch on a natural spawner -> Zyven Spawner item (config: `require-silk-touch-natural`)
- Place it: no mobs spawn, it produces drops + XP into storage (skeleton -> bones & arrows, etc.)
- Right-click: GUI (take items, collect XP, sell all). Right-click with same spawner item: stack.
- Silk Touch to pick up (returns the whole stack + stored items/XP)
- `/zs give <player> <type> [amount]`, `/zs list`, `/zs reload`, `/zs credits`
- Everything (drops, amounts, chances, prices, intervals, XP) is in `config.yml`
- 
