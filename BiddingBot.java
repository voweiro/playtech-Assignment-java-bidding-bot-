import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;

public class BiddingBot {

    private static final String DEFAULT_CATEGORY = "Sports";
    private static final long MIN_BID = 1L;
    private static final double EXPECTED_TOTAL_ROUNDS = 120000.0;
    private static final double MIN_EFFECTIVE_SPEND_FRACTION = 0.30;
    private static final double TARGET_SPEND_FRACTION = 0.42;

    private static final double[] VIEW_BUCKET_PRIOR = {
        1.10, 1.32, 1.55, 1.42, 1.20,
        1.02, 1.14, 1.30, 1.04, 0.88
    };

    private static String myCategory = DEFAULT_CATEGORY;
    private static long initialBudget;
    private static long currentBudget;
    private static long totalSpent;
    private static long totalWonAuctions;
    private static double totalPoints;
    private static int roundsSeen;
    private static int lastBidBucket = -1;
    private static long lastBidAmount;

    private static final SegmentModel segmentModel = new SegmentModel();

    public static void main(String[] args) {
        if (args.length != 1) {
            return;
        }

        try {
            initialBudget = Long.parseLong(args[0]);
            currentBudget = initialBudget;
        } catch (NumberFormatException ignored) {
            return;
        }

        System.out.println(myCategory);
        System.out.flush();

        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
             PrintWriter out = new PrintWriter(new BufferedWriter(new OutputStreamWriter(System.out)), false)) {
            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                if (line.charAt(0) == 'W') {
                    handleWin(line);
                } else if (line.charAt(0) == 'L') {
                    handleLoss();
                } else if (line.charAt(0) == 'S') {
                    handleSummary(line);
                } else if (line.indexOf('=') >= 0) {
                    handleBidRequest(line, out);
                }
            }
        } catch (IOException ignored) {
            // Exit quietly if stdin closes.
        }
    }

    private static void handleBidRequest(String line, PrintWriter out) {
        roundsSeen++;
        BidRequest request = parseRequest(line);
        int segment = segmentModel.getSegmentIndex(request);

        double score = estimateValueScore(request, segment);
        Bid bid = computeBid(score, request);

        lastBidBucket = segment;
        lastBidAmount = bid.maxBid;

        out.print(bid.startBid);
        out.print(' ');
        out.println(bid.maxBid);
        out.flush();
    }

    private static double estimateValueScore(BidRequest request, int segment) {
        double score = 0.20;

        boolean categoryMatch = myCategory.equals(request.videoCategory);
        int interestRank = getInterestRank(request.interests, myCategory);

        if (categoryMatch) {
            score += 2.8;
        }
        if (interestRank == 0) {
            score += 2.5;
        } else if (interestRank == 1) {
            score += 1.6;
        } else if (interestRank == 2) {
            score += 0.9;
        }
        if (categoryMatch && interestRank == 0) {
            score += 1.0;
        }

        if (request.subscribed) {
            score += 0.75;
        }

        score += ageWeight(request.age);
        score += genderWeight(request.gender);
        score += engagementWeight(request.viewCount, request.commentCount);
        score *= VIEW_BUCKET_PRIOR[getViewBucket(request.viewCount)];

        score *= segmentModel.getMultiplier(segment);

        if (!categoryMatch && interestRank < 0) {
            score *= 0.25; // Allow some high-signal non-Music bids through
        }

        return score;
    }

    private static Bid computeBid(double score, BidRequest request) {
        if (currentBudget <= 0) {
            return Bid.ZERO;
        }

        double spentFraction = initialBudget == 0 ? 1.0 : (double) totalSpent / initialBudget;
        double progress = Math.min(roundsSeen / EXPECTED_TOTAL_ROUNDS, 1.0);
        double targetSpend = targetSpendFraction(progress);
        double urgency = spentFraction >= targetSpend ? 1.0 : 1.0 + (targetSpend - spentFraction) * 6.0;

        boolean strongMatch = myCategory.equals(request.videoCategory) || getInterestRank(request.interests, myCategory) >= 0;
        double adjustedScore = score * urgency;

        if (!strongMatch && adjustedScore < 0.55) {
            return Bid.ZERO;
        }

        if (adjustedScore < 0.50) {
            return Bid.ZERO;
        }

        long bidCap = Math.max(MIN_BID, Math.min(currentBudget, pacingCap(progress)));
        long maxBid = Math.round(baseBidForScore(adjustedScore));
        maxBid = Math.min(maxBid, bidCap);

        if (maxBid < MIN_BID) {
            return Bid.ZERO;
        }

        long startBid = startingBid(maxBid, adjustedScore);
        return new Bid(startBid, maxBid);
    }

    private static double targetSpendFraction(double progress) {
        double floor = MIN_EFFECTIVE_SPEND_FRACTION * (0.35 + (0.65 * progress));
        double target = Math.min(TARGET_SPEND_FRACTION, 0.12 + (0.40 * progress));
        return Math.max(floor, target);
    }

    private static long pacingCap(double progress) {
        double capFraction;
        if (progress < 0.15) {
            capFraction = 0.040;
        } else if (progress < 0.50) {
            capFraction = 0.032;
        } else {
            capFraction = 0.044;
        }
        return Math.max(4L, (long) Math.ceil(currentBudget * capFraction));
    }

    private static double baseBidForScore(double score) {
        if (score < 1.3) {
            return 1.0;
        }
        if (score < 1.8) {
            return 2.0 + ((score - 1.3) * 4.0);
        }
        if (score < 2.8) {
            return 4.0 + ((score - 1.8) * 7.0);
        }
        if (score < 4.0) {
            return 11.0 + ((score - 2.8) * 11.0);
        }
        return 24.0 + ((score - 4.0) * 16.0);
    }

    private static long startingBid(long maxBid, double score) {
        if (maxBid <= 1) {
            return maxBid;
        }

        double ratio;
        if (score >= 4.5) {
            ratio = 0.75;
        } else if (score >= 3.2) {
            ratio = 0.60;
        } else if (score >= 2.2) {
            ratio = 0.45;
        } else {
            ratio = 0.30;
        }

        long startBid = (long) Math.floor(maxBid * ratio);
        if (startBid < 1) {
            startBid = 1;
        }
        if (startBid > maxBid) {
            startBid = maxBid;
        }
        return startBid;
    }

    private static double engagementWeight(long viewCount, long commentCount) {
        if (viewCount <= 0 || commentCount <= 0) {
            return 0.0;
        }

        double ratio = (double) commentCount / (double) viewCount;
        if (ratio >= 0.08) {
            return 1.4;
        }
        if (ratio >= 0.04) {
            return 0.95;
        }
        if (ratio >= 0.02) {
            return 0.55;
        }
        if (ratio >= 0.008) {
            return 0.20;
        }
        return -0.08;
    }

    private static double ageWeight(String age) {
        if ("18-24".equals(age)) {
            return 0.65;
        }
        if ("25-34".equals(age)) {
            return 0.58;
        }
        if ("35-44".equals(age)) {
            return 0.36;
        }
        if ("13-17".equals(age)) {
            return 0.24;
        }
        if ("45-54".equals(age)) {
            return 0.14;
        }
        return 0.04;
    }

    private static double genderWeight(String gender) {
        if ("F".equals(gender) && ("Beauty".equals(myCategory) || "ASMR".equals(myCategory) || "Cooking".equals(myCategory))) {
            return 0.18;
        }
        if ("M".equals(gender) && ("Sports".equals(myCategory) || "Video Games".equals(myCategory) || "Finance".equals(myCategory))) {
            return 0.18;
        }
        return 0.0;
    }

    private static void handleWin(String line) {
        long cost = parseLongAfterPrefix(line, 2);
        if (cost <= 0) {
            return;
        }

        currentBudget -= cost;
        if (currentBudget < 0) {
            currentBudget = 0;
        }
        totalSpent += cost;
        totalWonAuctions++;
        segmentModel.recordWin(lastBidBucket, cost);
    }

    private static void handleLoss() {
        segmentModel.recordLoss(lastBidBucket, lastBidAmount);
    }

    private static void handleSummary(String line) {
        String[] parts = line.split(" ");
        if (parts.length < 3) {
            return;
        }

        try {
            double points = Double.parseDouble(parts[1]);
            long spent = Long.parseLong(parts[2]);
            totalPoints += points;
            segmentModel.absorbWindow(points, spent);
        } catch (NumberFormatException ignored) {
            // Ignore malformed summary lines.
        }
    }

    private static long parseLongAfterPrefix(String text, int startIndex) {
        try {
            return Long.parseLong(text.substring(startIndex).trim());
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static int getInterestRank(String interests, String target) {
        if (interests == null || interests.isEmpty()) {
            return -1;
        }

        int start = 0;
        int rank = 0;
        for (int i = 0; i <= interests.length(); i++) {
            if (i == interests.length() || interests.charAt(i) == ';') {
                String token = interests.substring(start, i).trim();
                if (token.equals(target)) {
                    return rank;
                }
                start = i + 1;
                rank++;
            }
        }
        return -1;
    }

    private static int getViewBucket(long viewCount) {
        if (viewCount < 5_000L) {
            return 0;
        }
        if (viewCount < 20_000L) {
            return 1;
        }
        if (viewCount < 50_000L) {
            return 2;
        }
        if (viewCount < 100_000L) {
            return 3;
        }
        if (viewCount < 250_000L) {
            return 4;
        }
        if (viewCount < 500_000L) {
            return 5;
        }
        if (viewCount < 1_000_000L) {
            return 6;
        }
        if (viewCount < 5_000_000L) {
            return 7;
        }
        if (viewCount < 20_000_000L) {
            return 8;
        }
        return 9;
    }

    private static BidRequest parseRequest(String line) {
        BidRequest request = new BidRequest();
        int cursor = 0;
        while (cursor < line.length()) {
            int equals = line.indexOf('=', cursor);
            if (equals < 0) {
                break;
            }
            int comma = line.indexOf(',', equals + 1);
            if (comma < 0) {
                comma = line.length();
            }

            String key = line.substring(cursor, equals);
            String value = line.substring(equals + 1, comma);

            if ("video.category".equals(key)) {
                request.videoCategory = value;
            } else if ("video.viewCount".equals(key)) {
                request.viewCount = safeParseLong(value);
            } else if ("video.commentCount".equals(key)) {
                request.commentCount = safeParseLong(value);
            } else if ("viewer.subscribed".equals(key)) {
                request.subscribed = "Y".equals(value);
            } else if ("viewer.age".equals(key)) {
                request.age = value;
            } else if ("viewer.gender".equals(key)) {
                request.gender = value;
            } else if ("viewer.interests".equals(key)) {
                request.interests = value;
            }

            cursor = comma + 1;
        }
        return request;
    }

    private static long safeParseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static final class Bid {
        private static final Bid ZERO = new Bid(0L, 0L);

        private final long startBid;
        private final long maxBid;

        private Bid(long startBid, long maxBid) {
            this.startBid = startBid;
            this.maxBid = maxBid;
        }
    }

    private static final class BidRequest {
        private String videoCategory = "";
        private long viewCount;
        private long commentCount;
        private boolean subscribed;
        private String age = "";
        private String gender = "";
        private String interests = "";
    }

    private static final class SegmentModel {
        private static final int SEGMENT_COUNT = 20;

        private final double[] learnedPoints = new double[SEGMENT_COUNT];
        private final long[] learnedSpent = new long[SEGMENT_COUNT];
        private final long[] windowSpent = new long[SEGMENT_COUNT];
        private final int[] windowWins = new int[SEGMENT_COUNT];
        private final int[] totalWins = new int[SEGMENT_COUNT];

        private int getSegmentIndex(BidRequest request) {
            int viewBucket = getViewBucket(request.viewCount);
            int genderBucket = "F".equals(request.gender) ? 1 : 0;
            return (viewBucket * 2) + genderBucket;
        }

        private void recordWin(int index, long cost) {
            if (index < 0 || index >= SEGMENT_COUNT) {
                return;
            }
            windowSpent[index] += cost;
            windowWins[index]++;
        }

        private void recordLoss(int index, long attemptedBid) {
            if (index < 0 || index >= SEGMENT_COUNT || attemptedBid <= 0) {
                return;
            }
            windowSpent[index] += Math.min(1L, attemptedBid / 20L);
        }

        private void absorbWindow(double points, long reportedSpent) {
            long totalWindowSpent = 0L;
            for (int i = 0; i < SEGMENT_COUNT; i++) {
                totalWindowSpent += windowSpent[i];
            }

            if (totalWindowSpent > 0 && points > 0.0) {
                for (int i = 0; i < SEGMENT_COUNT; i++) {
                    if (windowSpent[i] == 0L) {
                        continue;
                    }
                    double share = (double) windowSpent[i] / (double) totalWindowSpent;
                    learnedPoints[i] += points * share;
                    learnedSpent[i] += windowSpent[i];
                    totalWins[i] += windowWins[i];
                }
            }

            if (reportedSpent > 0 && totalWindowSpent == 0) {
                int fallback = 0;
                learnedSpent[fallback] += reportedSpent;
            }

            for (int i = 0; i < SEGMENT_COUNT; i++) {
                windowSpent[i] = 0L;
                windowWins[i] = 0;
            }
        }

        private double getMultiplier(int index) {
            if (index < 0 || index >= SEGMENT_COUNT) {
                return 1.0;
            }

            if (learnedSpent[index] < 40L || totalWins[index] < 4) {
                return 1.0;
            }

            double roi = learnedPoints[index] / Math.max(1L, learnedSpent[index]);
            if (roi >= 8.0) {
                return 1.45;
            }
            if (roi >= 5.0) {
                return 1.25;
            }
            if (roi >= 3.0) {
                return 1.10;
            }
            if (roi <= 0.8) {
                return 0.55;
            }
            if (roi <= 1.3) {
                return 0.78;
            }
            return 1.0;
        }
    }
}
