# One wire: signal taps, a two-part sensor, events, scoped cards, tag pegs, order tickets

Status: implemented (2026-10-31), with the differences under "As built" · Date: 2026-10-31

## Why

Parts talk to each other three ways today, and none of them is general:

| Wire | Carries | Who can produce it |
|---|---|---|
| Redstone | 0–15, lossy | Every block |
| Signal Card | A full `SignalValue` (number or portfolio), wireless | Only the Signal Mixer (`SignalMixerBlockEntity.printCard`) |
| Trade / Watch Card | Permission to act on, or look at, an account | The vault and the Access screen |

Each block also has its own built-in readings. `SensorMode` has 27 modes, and they repeat sources the mixer already
has (`PRICE`, `CHANGE`, `FUNDING`, `METRIC`) crossed with tests it already has (`COMPARE`, `MAP`). So adding a new
reading means adding it to one block, and nothing else can use it. Limits on what a card may do are stored on
per-player grants (`Account.Grant`), not on the card, so the same card does different things depending on who
placed the block.

The goal is to have **one value wire (the Signal Card) that every block can produce and consume**, readings defined
once in `SignalEngine`, and permissions that travel with the card.

## Phases

| Phase | What | Depends on |
|---|---|---|
| 1 | Signal taps and the two-part Market Sensor | none |
| 2 | Event values and event triggers | 1 |
| 3 | Scoped Trade Cards | none |
| 4 | UNIT.XYZ pegs by item tag, scoreboard tap | none |
| 5 | Order Tickets | 1 (bindings), 2 (event triggers) |

Each phase ships on its own and leaves existing worlds working.

---

## Phase 1: signal taps and the two-part sensor

### 1.1 Every reading is a `SignalNode`

A block that reads something owns a `SignalNode` in `SignalRegistry`, the same way a mixer does (`nodeId` stored in
the block entity, created on first tick, removed in `dropContents`). The node holds the reading's definition, so it
keeps evaluating while the block is unloaded, which is true of mixers today.

| Block | Its node | Value |
|---|---|---|
| Market Sensor | Its reading (see 1.3) | The raw reading, before the test |
| Account Display | `METRIC` of its card's account, with its chosen figure and coin | The figure |
| Copy Desk | New source `DESK` (text = desk id) | Tracking error, 0..1 of equity |
| Signal Mixer | Unchanged | Unchanged |

A node made by a block is marked `SignalNode.tap = true`. Its op and parameters are written by the block's own
screen. The mixer screen won't edit a tap node, and a tap node can't be one of a mixer's inputs if that would loop
(`SignalEngine.dependsOn` already catches this).

### 1.2 Printing

Every block from 1.1 gets a **Print Signal Card** footer button, which goes through the same path as the mixer's:
- It needs a Blank Card unless `canBuild(player)`.
- The card's `SignalRef` name is the block's label, e.g. "BTC price", "Treasury · Equity", "Desk → Tour · tracking".
- It sends `vault_result` "Printed a Signal Card for …".

The server handler becomes one generic `print_signal` packet (`pos`), handled for any `HcBlockEntity` that implements
the new interface `SignalTap { @Nullable SignalNode tapNode(); }`. The mixer's existing print path moves onto it.

### 1.3 The Market Sensor is a reading plus a test

The sensor's config becomes two choices instead of one mode.

**Reading.** This is the sensor's tap node. It is a `SignalOp` source, or the inserted Signal Card.

| Reading | Op | Needs |
|---|---|---|
| Price | `PRICE` | coin |
| Change % | `CHANGE` (a = window s) | coin |
| Funding APR | `FUNDING` | coin |
| Position $ | **new** `POSITION` (signed USD notional) | coin, card |
| Side | **new** `SIDE` (+1 / 0 / −1) | coin, card |
| Position PnL | **new** `POSITION_PNL` (USD; NONE while flat) | coin, card |
| Open orders | **new** `OPEN_ORDERS` (count) | coin, card |
| Spread | **new** `SPREAD` (bps) | coin |
| Book tilt | **new** `BOOK_TILT` (bid share of top 10 levels, 0..1) | coin |
| Account figure | `METRIC` (text = `AccountMetric`) | card |
| Fill | **new** `FILL` (event, phase 2) | coin, card |
| NYSE open | `MARKET_HOURS` | none |
| NYSE bell | **new** `BELL` (event, phase 2) | none |
| Signal Card | The inserted card's node | Signal Card |

The new sources move logic that is now private to `MarketSensorBlockEntity` (`position`, `pnl`, the spread, tilt
and open-order reads) into `SignalEngine.Env`, as `position(in, coin)`, `positionPnl(in, coin)`,
`openOrders(in, coin)`, `spreadBps(coin)` and `bookTilt(coin)`. `SignalRegistry` implements them, and the test `Env`
in `SignalEngineTest` fakes them.

**Market on the node.** `SignalNode` gains `public String market = ""`. Account and per-coin ops use it instead of
packing the coin into `text`. `METRIC` reads `market` for per-coin figures (`AccountMetric.POSITION`). Old nodes
upgrade with `market = ""`, and `METRIC` falls back to the block's coin when the field is blank.

**Test.** This stays local to the sensor, because it turns a value into redstone:

| Test | Output | Params |
|---|---|---|
| At least | 15 if v ≥ a | a |
| At most | 15 if v ≤ a | a |
| Analog | 0..15 across a..b | a, b |
| Rises by | 15 if v − v(t − window) ≥ a | a, window |
| Falls by | 15 if v(t − window) − v ≥ a | a, window |
| Pulse | 15 for 1 s when an event arrives (phase 2) | none |

There is an **Either side** toggle (default off) that tests |v|. NONE gives 0 for every test, which is how the
sensor behaves today when a card is revoked or no price has arrived.

"Rises by" and "Falls by" need a value from the past. This becomes a new transform op, `DELTA` (input now minus input
`a` seconds ago, max 3600). It reuses the history buffer that `DELAY` already keeps. The sensor's test reads
`DELTA` of its reading internally. The mixer also gets `DELTA` as an op.

**Presets.** The mode picker stays as it is: a preset menu that sets the reading and the test. Every one of the 27
old modes is a preset, so the sensor is no harder to use.

### 1.4 Migration of the 27 modes

`MarketSensorBlockEntity.readConfig` maps a stored `Mode` to a reading and a test once, then writes the new fields.
`SensorMode` remains as the preset table.

| Old mode | Reading | Test |
|---|---|---|
| PRICE_ABOVE / PRICE_BELOW | Price | At least / At most a |
| RISING / FALLING | Change % (window b) | At least \|a\| / At most −\|a\| |
| ANALOG | Price | Analog a..b |
| FUNDING_ABOVE / FUNDING_BELOW | Funding APR | At least / At most a |
| IN_LONG / IN_SHORT | Side | At least 1 / At most −1 |
| PNL_ABOVE / PNL_BELOW | Position PnL | At least / At most a |
| FILLED | Fill | Pulse |
| OPEN_ORDERS | Open orders | At least a |
| MARGIN_USAGE | Account figure `MARGIN_USAGE` | Analog 0..1 |
| EXPOSURE | Position $ | Analog 0..a, either side |
| SPREAD_BELOW | Spread | At most a |
| BOOK_TILT | Book tilt | Analog 0..1 |
| ACCOUNT_ABOVE / BELOW / ANALOG | Account figure | At least / At most / Analog |
| ACCOUNT_RISE / ACCOUNT_DROP | Account figure | Rises by / Falls by (a, window b) |
| SIGNAL_ABOVE / BELOW / ANALOG | Signal Card | At least / At most / Analog |
| MARKET_OPEN | NYSE open | At least 1 |
| MARKET_BELL | NYSE bell | Pulse |

Until phase 2 lands, `FILLED` and `MARKET_BELL` keep their current code path, with the reading shown as
"(built in)" and the Print button disabled for them.

### 1.5 Security

A tap of an account reading behaves like a mixer `METRIC` input does today:
- The node's account input carries the block's card id and level.
- `AccessPolicy.checkCard` is re-checked on every evaluation.
- Revoking or ejecting the card turns the tap, and every Signal Card printed from it, to NONE.

Anyone holding the printed Signal Card can see that figure. The Print button's tooltip says this, as the mixer's
should: "Anyone holding this card can read Treasury's equity".

### 1.6 Acceptance

- `SignalEngineTest`:
  - Covers each new source against a fake `Env`.
  - Covers `DELTA` across its window.
  - Checks that NONE passes through every test.
- A migration test: every `SensorMode` maps to a reading and a test that give the same redstone output as the old
  `evaluate` for a set of fixed inputs. This is a table test over all 27 modes.
- UiTour:
  - Prints a Signal Card from a Price sensor and from an Account Display.
  - Puts both into a mixer `BLEND` and reads a non-NONE value.
  - Checks that ejecting the display's card turns the printed card NONE.
- Lint shows 0 issues at both scales. The sensor screen gains a reading row and a test row in place of the mode row.

---

## Phase 2: event values

### 2.1 Type

```java
/** Something happened: a fill, a bell. seq rises by one per occurrence; at is when the latest one happened. */
record Event(long seq, long at, String what) implements SignalValue {}
```

- `asNumber` is 1 within 1 s of `at`, else 0. This keeps sensors and executor bindings meaningful.
- Sources: `FILL` (account + market; `seq` = fill count on that market; `what` = "BUY 0.01 BTC @ 61,200"), `BELL`
  (`what` = "open" / "close").
- **New source `ORDER`** (text = executor id): the order result of that executor's last pulse. `what` = "filled" /
  "rejected: …". This lets a Signal Card from one executor drive the next one.
- Transforms: `GATE` passes an event when its gate is open. `DELAY` delays it. `COMPARE` and `MAP` use `asNumber`.
  `BLEND`, `SCALE` and the portfolio ops give NONE for an event.

### 2.2 Event triggers

`ExecutorInput.Source` gains `SIGNAL_EVENT` ("Signal event"), offered only as the executor's **Trigger**, not as a
value source. When it's chosen:
- The executor fires once each time the Signal Card's `seq` advances, as a rising edge would. It fires at most once
  per second.
- Redstone still fires it, unless "Signal only" is set.

The Kill Switch gains the same trigger, e.g. "flatten when the leader's position flips".

`TradeExecutorBlockEntity` gets a tap node (phase 1 interface) with op `ORDER`, so Print Signal Card works on
executors too.

### 2.3 Acceptance

- Engine test: a `FILL` sequence through `GATE` and `DELAY`.
- UiTour:
  - Executor A fills.
  - Executor B, triggered by A's printed card, fires once.
  - The comparator on B reads 15.

---

## Phase 3: scoped Trade Cards

### 3.1 Model

`Account.CardRecord` gains a scope with the same fields a `Grant` has:

```java
public static final class Scope {
	public java.util.List<String> markets = new java.util.ArrayList<>(); // empty = all
	public double maxOrderUsd;   // 0 = no limit
	public int maxLeverage;      // 0 = any
	public boolean reduceOnly;
	public long expiresAt;       // 0 = never
}
```

The server's `CardRecord` is the only source of truth. The item carries a copy in a new component,
`hypercraft:card_scope`, used only for its tooltip, because items can be edited in creative or duplicated.

### 3.2 Enforcement

`AccessPolicy.checkOrder` and `checkLeverage` take the card's record when the order came through a block. The order
must pass both the block owner's level or grant **and** the card's scope, so limits intersect. The scope checks reuse
the grant checks' code and messages, with "This card" in place of "Your access": "This card is reduce-only", "This
card doesn't include SOL", "This card is capped at $500 per order".

A Watch Card's scope may set only `expiresAt`. The other fields are ignored for reading.

### 3.3 Printing and seeing

- In the Access screen, **Print Trade Card…** opens the same fields the grant editor has (markets, max order, max
  leverage, reduce-only, expires). Leaving every field blank prints an unscoped card, as today.
- The vault's right-click print stays unscoped.
- The tooltip shows "Trade Card · BTC, ETH · $500/order · reduce-only · expires Fri 18:00".
- The `BlockConfigScreen` header appends "(scoped)", and its tooltip lists the limits.
- The Access screen's printed-card list shows each card's scope. The scope can be tightened later but never
  loosened: to loosen it, print a new card.

### 3.4 Acceptance

- `AccessPolicyTest`:
  - For the owner with a scoped card: each field, and the intersection with a grant.
  - An expired card's message.
- UiTour: an executor with a BTC-only card refuses an ETH order with "This card doesn't include ETH" and accepts a
  BTC one.

---

## Phase 4: connections outside the mod

### 4.1 UNIT.XYZ pegs by item tag

The keys of `UnitConfig.Token.items` may be tags: `"#c:ingots/gold": 1.0`.
- `tokenFor` takes the stack. It checks the exact id first, then tags in file order.
- `withdraw` must still list item ids, because a tag can't be handed out.
- `accepted()` and the bridge's grid show a tag as its first loaded member's icon, with the tag in the tooltip.

`VERSION` 5 adds the vanilla items' common tags beside their ids (`c:ingots/gold`, `c:ingots/copper`,
`c:ingots/iron`, `c:gems/diamond`, `c:gems/emerald`, `c:ingots/netherite`, `c:dusts/redstone`, `c:gems/quartz`,
`c:dusts/glowstone`, `c:rods/blaze`). Modded variants in those tags then count automatically.

It also adds candidate tokens for metals Minecraft doesn't have (`c:ingots/silver`, `c:ingots/platinum`,
`c:ingots/uranium`, `c:ingots/nickel`, `c:ingots/tin`, `c:ingots/aluminum`). Each is kept only if its reference
market is listed at startup and the tag has members. Otherwise it's left out with one log line, the same way
delisted `xyz:WHEAT` was dropped.

Test (`UnitConfigTest`): an id wins over a tag, a tag match is found, and upgrading from v4 adds the tags without
touching edited amounts.

### 4.2 Scoreboard tap

`/hl signal score <objective> [scale]` is run while holding a Signal Card and needs permission level 2.
- Each second, `SignalRegistry` writes `round(asNumber × scale)` (scale defaults to 1) to the score holder named
  after the signal.
- NONE writes nothing.
- `/hl signal score clear` removes the binding.
- Bindings are saved with the registry.

This lets command blocks, datapacks and other mods read any signal.

### 4.3 Not now

- **CC:Tweaked peripheral.** It would add a dependency on another mod. Revisit if players ask for scripting. The
  `SignalTap` and `AccessPolicy` seams it would need exist once phases 1 and 3 are in.
- **One "Rebalancer" in place of Copy Desk, Kill Switch and Leverage Dial.** On its own this is mostly renaming.

---

## Phase 5: Order Tickets

### 5.1 Item

`hypercraft:order_ticket` (stack 1) has a component `OrderSpec` with what the executor's config holds today:
- coin, side, order type, repeat
- the six `ExecutorInput` bindings (source, lo, hi)
- reduce-only

A ticket carries no account and no authority, so copying or losing one is harmless. Two ways to make one:
- Craft paper with any ticket to copy it.
- Use the Terminal's **Save as ticket** button, which writes the current order form.

### 5.2 Executor

- The executor has a ticket slot. When there's a ticket, the config screen edits the ticket in place: Save writes
  `OrderSpec` back to the item.
- With no ticket, the executor uses its own stored config. Existing executors are unchanged.
- **Write to ticket** exports the stored config onto a blank ticket.
- The Executor gains a **Cycle** option. After each pulse, the used ticket goes out the bottom face into a container
  (if there is one), and the next ticket is pulled from a container above. A chest of tickets then becomes a
  sequence of legs.
- Hoppers can insert into the ticket slot only when it's empty.

Face and Signal bindings stay relative to the executor that runs the ticket, so a ticket works in any executor.

### 5.3 Acceptance

- Round trip: executor config → ticket → second executor gives an identical `toRequest`.
- UiTour: an executor in Cycle mode with two tickets in a chest above places a BTC buy, then an ETH buy, on two
  pulses.
- Hopper and dispenser behaviour is covered by the same tour.

---

## Docs

Each phase updates the README section for the block it touches, the CHANGELOG, and the Build tab checklist where
it adds a step (Print Signal Card; Save as ticket).

---

## As built

Where the build differs from the text above, and why:

- **Rises and Falls** use the sensor's own history of its reading over the window, as account rise/drop always did,
  rather than a `DELTA` node in the registry. `DELTA` ships as a mixer op ("Change by").
- **METRIC** also accepts a per-coin figure as `POSITION:BTC` in its text, so a mixer can name one without a market field.
- **ORDER** events fire only for orders that went through, so a chain doesn't run on a rejected order. Its value is
  "nothing yet" (an empty event) before the first order, so a watcher's baseline is set and the first order counts.
- A **Signal Card reading** on a sensor, and a Signal Card in an Account Display, tap the card through a one-input
  `BLEND`, which passes portfolios, numbers and events through unchanged.
- **Kill Switch** gained a Signal Card slot (right-click one in) for its event trigger.
- **Scoped cards**: block headers show "(scoped)", and the limits show in the status line when the block has nothing
  else to say. The Access screen's **Limit** button tightens a printed card to the limits typed under Print a card.
- **Mod metals** are silver, platinum and uranium only, each priced by a market a default token already uses
  (`xyz:SILVER`, `xyz:PLATINUM`, `xyz:URNM`), so no listing check is needed: they join when a mod puts items in the tag.
  Nickel, tin and aluminum have no market to price them by.
- **Order Tickets**: a blank is crafted from paper, paper and redstone (two). The terminal's link is **▤ ticket** in the
  order panel's header; shift-click writes a SELL ticket. A cycling executor with nothing loaded takes the first
  ticket from above before it fires, so a chest of tickets runs in order from the first pulse.
- The ticket round trip is checked in the UI tour, not a unit test: it needs a block entity.
