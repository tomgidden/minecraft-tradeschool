Proof-of-concept of an alternative Villager Trading mechanic using a teaching system, to encourage exploration and reduce random grind. Teach librarians, weaponsmiths, toolsmiths, armourers, and fletchers by selling them enchanted items — they'll learn and resell what you teach!

## Minecraft 26.3

**This build is for Minecraft 26.3 only.** 26.3 changed how villager trades and loot tables work in ways that cannot be supported from the same jar, so 26.1.2 and 26.2 stay on the previous release.

### Changes

- Villagers now level up while you are trading with them; rather than having to close and reopen trading.
  - A villager taught enough to earn a promotion now levels up immediately, with the usual particles, instead of waiting for you to close the screen.
  - A villager that levels up while you have its screen open will offer to learn a new trade straight away. Previously you had to close the screen and reopen it.
  - Fixed a client crash when a villager's trades changed while you had one selected.

- Suspicious sand and gravel finally yield something.  The previous version had the better loot replaced by the vanilla loot. Archaeology is now _worth it_.
  - Desert wells and pyramids, trail ruins and ocean ruins are all affected.

- More places to find enchanted books
  - **Abandoned camps**, new in 26.3, are now supported — both the common and the secret chest;
  - Shipwreck supply and map chests;
  - More trial chamber chests;
  - All eleven village profession chests, which are now matched to the villager's profession.

- **Village houses are _mildly_ biome-specific.**
  The biome-specific enchantments from the _Villager Trade Rebalance_ experiment (which were exclusive in that experiment) should subtly influence the probabilities in the villages. For example, a Snow Village's enchanted books are now slightly more likely to be "Frost Walker" than "Fire Protection", and a Desert Village's are the opposite. We're only talking a couple of percent here, though.

- Reliability.  There were a lot of bugs in the previous version where loot (for example) wasn't distributed as designed.

- UI.  The mod has richer GUI aspects now, including better messaging when trades are learned, and better explanations.  These are configurable, too.
