# Trade School — FAQ & Design Notes

## Teaching Mechanics

### Why can't I teach a villager the same trade twice to upgrade it?

Each villager learns exactly one trade per career level. Once they've learned at level 1, they won't accept further teaching until they level up. This is intentional: the incentive is to level your villager up through trading so they can learn better versions of the trade at higher levels — a level 2 weaponsmith can learn a stone sword with sharpness 2, while a level 1 can only learn a wooden sword with sharpness 1.

Allowing re-teaching at the same level would let players grind a villager's existing knowledge, which undermines the progression design.

### Why does the villager only learn one item per level?

For the same reason — scarcity and progression. A single learned trade per level keeps villagers specialised and meaningful. If a villager could learn six trades at level 1, there would be little incentive to advance them or seek out multiple villagers.

### Why can villagers only learn items appropriate to their profession and level?

A novice weaponsmith doesn't have the skill to make diamond weapons — they can only work with wood. The material tier system maps villager level to craftable materials:

- **Weaponsmith/Toolsmith:** wood (1) → stone (2) → copper (3) → iron (4) → diamond (5)
- **Armorer:** leather (1) → copper/gold (2) → chainmail (3) → iron (4) → diamond (5)

If you teach a level 1 weaponsmith with a diamond sword, they learn the *pattern* but can only produce a wooden equivalent. You get the downgraded item back so you can keep your diamond sword.

### Why is gold treated specially?

Gold tools and weapons are valid at any weaponsmith/toolsmith level and kept as gold (not downgraded to wood). This reflects gold's real-world value as a noble metal — it's just unusual to work with, not inferior. A novice smith can work gold even if they can't work iron yet.

For armorers, gold armor is valid at level 2 and above (copper tier), and is downgraded to leather at level 1. Gold armor is more complex to make than leather but simpler than chainmail.

### Why can't I teach a villager a netherite item?

Netherite is too rare and powerful to be within the scope of what a village artisan could learn. It's not simply a material tier above diamond — it requires ancient debris, smithing templates, and a smithing table. Accepting netherite would trivialise the end-game progression. Netherite items are never accepted, never counted toward the slot cap, and can't even be used to teach a level 5 master the diamond equivalent.

### Why does the villager give me emeralds instead of returning my item when I fully teach them?

If the villager can fully replicate your item (no material downgrade, no enchantment capping), keeping the item and paying you nothing would make teaching free. The emerald payment (half the sell price of the learned item, rounded up) represents the villager compensating you for your expertise — you're effectively selling them a recipe.

If the trade results in a downgrade (lower material, capped enchantments, or stripped customisation), you get the downgraded item back instead. This way you can keep iterating: teach at level 1, get a wooden sword back, level the villager up, teach again with the same diamond sword.

### Why is the emerald payment half the sell price rather than the full price?

The villager is paying for *knowledge*, not the item itself. They'll recoup their investment by selling the learned item many times over. Half price reflects this asymmetry — you're sharing knowledge, not selling goods.

---

## Customisation & Item Components

### Why are trims, dyes, and custom names stripped from learned trades?

Trims and dyes are cosmetic personalisation applied by the player — they're not part of the smith's craft knowledge. A weaponsmith learns *how to make a diamond sword with sharpness*, not *how to make your specific named sword with your personal trim*. The learned trade produces a clean base item.

If the only difference between your submitted item and the learned item is the trim/dye/name being stripped, you receive the clean item back (not emeralds) to make it clear something was changed. This also prevents using the teaching mechanic as a way to "launder" a customised item into a clean copy.

### Why is damage preserved in the learned trade but not in the result item shown in the UI preview?

Damage and repair cost are gameplay attributes, not cosmetic ones — a sword with 500 durability used is genuinely different from a fresh one. The villager learns the item *as you handed it to them*, damage included, and will sell that exact template.

However, when multiple identical items (same enchantments, different damage) are collapsed into a single trade offer, the UI preview is pre-built from the first item scanned when you opened the trading screen. There's no way to update the preview image dynamically as you slot different items into the trade input. The item you actually receive is always correct — it matches what you submitted — but the damage bar shown in the result slot of the UI may not match. This is a known cosmetic limitation.

### Why can't the villager learn banner patterns on shields?

Banner patterns are player-applied customisation, the same as trims on armor. The armorer learns *shield*, not *your flower-patterned shield*. The pattern is stripped and a clean shield trade is learned.

---

## Slot Behaviour

### Why does the trading UI show multiple offers when I have several teachable items?

When you open the trading screen with a tradeschool villager, all teachable items you're carrying (up to 6, scanned in order: mainhand → offhand → armor slots → hotbar) become individual teach offers. This lets you teach the villager with whichever item you choose in a single UI session rather than having to re-open the screen for each one.

### If I have two identical swords (same enchantments) with different damage, why is there only one offer?

Two items that would produce the same learned trade are collapsed into one offer to avoid a confusing list of visually identical options. The predicate on the cost slot matches either sword, and the result you receive will correctly reflect whichever one you actually submitted. The only quirk is that the result's durability bar in the UI preview may not match — see above.

### Why is there a cap of 6 teach offers?

Practical UX — a trading screen with 10+ offers is overwhelming, and the player can always re-open the screen. The cap of 6 matches Minecraft's own convention for the maximum number of simultaneous trade slots shown cleanly.

### Why does the villager ignore items in my main inventory (below the hotbar)?

The scan covers mainhand, offhand, all four armor slots, and hotbar slots 1–8 — the slots most naturally accessible during normal play. Main inventory slots (the 3×9 grid above the hotbar) require the player to have deliberately placed items there before approaching the villager, which is less natural. This keeps the feature discovery simple: carry the item you want to teach, right-click the villager.

---

## Enchantments

### Why are enchantments capped at the villager's level?

A level 1 weaponsmith can understand basic weapon-craft but not master-level enchantments. The cap (enchantment level ≤ villager level, with single-level enchantments like Mending requiring level 3+) means there's genuine benefit to levelling your villager up before teaching them your best gear. A level 5 master can learn and sell any enchantment at full strength.

### Why can't I teach a villager Curse of Copyright?

The Curse of Copyright is an enchantment specifically designed to prevent an item from being used for teaching — a form of DRM on rare or loot-generated items. If an item carries the curse, the villager refuses to study it, and will react with displeasure. This is the mechanism that limits the supply of learnable high-level enchantments and keeps exploration rewarding.

---

## Known Limitations

### The result item's durability bar in the UI doesn't always match what I receive

**Why:** The `MerchantOffer` result stack is fixed when the trade screen opens (built from the first matching item scanned in your inventory). Minecraft has no mechanism to update the displayed result dynamically based on what you place in the input slot.

**Actual behaviour:** The item you receive is always correct — damage and repair cost are patched at the moment you take the result, based on what you actually submitted.

**Ideal behaviour:** The result preview would update live as you slot different items in. This would require either a custom trading screen (significant work) or a Minecraft API hook that doesn't currently exist.

### The villager's held item preview (what they show you before trading) always shows the first teachable item found

**Why:** The preview is updated on a server AI tick, showing the first teachable item found in scan order. If you have a diamond sword in your mainhand and a wooden sword in your hotbar, the villager will always hold up the diamond sword's downgraded version as a preview, regardless of which offer you end up using.

**Ideal behaviour:** The preview would cycle through or respond to what the player is currently holding. Not currently implemented.
