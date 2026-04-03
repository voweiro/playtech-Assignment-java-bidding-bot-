# Playtech Internship 2026  Attention Economy Bidding Bot

A Java bidding bot for the Playtech Summer 2026 internship home assignment. The bot participates in a simulated real-time ad auction, competing against other bots to win advertising slots on videos.

## Chosen Category
**Music**

## How to Compile and Run
```bash
javac BiddingBot.java
jar cfe my-bot.jar BiddingBot BiddingBot*.class
```
Then place the jar in a subdirectory under the harness working directory and run:
```bash
java -jar harness.jar
```

## Strategy Overview
The bot scores every ad slot before bidding based on category and interest alignment, viewer demographics, video engagement ratio, and view count bracket. Slots below a quality threshold are skipped entirely  the value distribution is Pareto-shaped, so most slots aren't worth fighting for. Budget is concentrated on the rare high-value slots.

An adaptive segment model tracks points-per-ebuck across different slot types every 100 rounds and adjusts bid multipliers based on observed returns, so the bot improves its targeting over the course of the auction.

## Test Results (vs provided harness bots)

| Category | Score | Notes |
|----------|-------|-------|
| Music | 0.451 | Solo in category |
| Video Games | 0.449 | Solo in category |
| DIY | 0.444 – 0.448 | Competed against dumb bot in same category |
| Sports | 0.438 | Competed against dumb bot in same category |
| Music | 0.432 | Competed against dumb bot in same category |
| Kids | 0.366 | Solo in category |
| Cooking | 0.359 | Competed against dumb bots in same category |
| Beauty | 0.330 | Competed against dumb bot in same category |
| ASMR | 0.291 | Competed against dumb bot in same category |
| Finance | 0.223 | Competed against dumb bots in same category |
| Best dumb bot (any category) | ~0.240 | For reference |
| silly-gpt (Finance) | 0.011 – 0.020 | For reference |

The bot consistently scores approximately **2x the dumb bots** across all categories tested. Finance is clearly the weakest category regardless of strategy  confirmed by both the provided silly-gpt bot and independent testing.
