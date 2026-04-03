# Playtech Internship 2026 — Attention Economy Bidding Bot

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
The bot scores every ad slot before bidding based on category/interest alignment, viewer demographics, video engagement ratio, and view count. Slots below a quality threshold are skipped entirely — the value distribution is Pareto-shaped, so most slots aren't worth fighting for. Budget is concentrated on the rare high-value slots.

An adaptive segment model tracks points-per-ebuck across different slot types every 100 rounds and adjusts bid multipliers based on observed returns, so the bot improves its targeting over the course of the auction.

 Test Results (vs provided harness bots)
| Bot | Score |
|-----|-------|
| **My bot (Music)** | **0.432** |
| dumb-retro (best dumb bot) | ~0.220 |
| silly-gpt (Finance) | ~0.020 |

