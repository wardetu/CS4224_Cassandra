package cs4224;

import com.google.inject.Inject;
import cs4224.transactions.*;
import cs4224.utils.Statistics;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;

public class Driver {
    public static long numQueries = 0;
    public static long skippedTransactions = 0;

    private final NewOrderTransaction newOrderTransaction;
    private final PaymentTransaction paymentTransaction;
    private final DeliveryTransaction deliveryTransaction;
    private final OrderStatusTransaction orderStatusTransaction;
    private final StockLevelTransaction stockLevelTransaction;
    private final PopularItemTransaction popularItemTransaction;
    private final TopBalanceTransaction topBalanceTransaction;
    private final RelatedCustomerTransaction relatedCustomerTransaction;

    @Inject
    public Driver(NewOrderTransaction newOrderTransaction, PaymentTransaction paymentTransaction,
                  DeliveryTransaction deliveryTransaction, OrderStatusTransaction orderStatusTransaction,
                  StockLevelTransaction stockLevelTransaction, PopularItemTransaction popularItemTransaction,
                  TopBalanceTransaction topBalanceTransaction, RelatedCustomerTransaction relatedCustomerTransaction) {
        this.newOrderTransaction = newOrderTransaction;
        this.paymentTransaction = paymentTransaction;
        this.deliveryTransaction = deliveryTransaction;
        this.orderStatusTransaction = orderStatusTransaction;
        this.stockLevelTransaction = stockLevelTransaction;
        this.popularItemTransaction = popularItemTransaction;
        this.topBalanceTransaction = topBalanceTransaction;
        this.relatedCustomerTransaction = relatedCustomerTransaction;
    }

    void runQueries(String queryFilename) throws Exception {
        File queryTxt = new File(queryFilename);

        Scanner scanner = new Scanner(queryTxt);
        BaseTransaction transaction;

        Statistics calculator = Statistics.getStatisticsCalculator();

        long start, end, lStart, lEnd, lapse, totalLapse;

        start = System.nanoTime();
        while (scanner.hasNext()) {
            numQueries++;

            String line = scanner.nextLine();
            String[] parameters = line.split(",");
            String[] lines = new String[0];

            switch (parameters[0]) {
                case "N":
                    transaction = newOrderTransaction;
                    int moreLines = Integer.parseInt(parameters[4]);
                    lines = new String[moreLines];
                    for (int i = 0; i < moreLines; i++) {
                        lines[i] = scanner.nextLine();
                    }
                    break;
                case "P":
                    transaction = paymentTransaction;
                    break;
                case "D":
                    transaction = deliveryTransaction;
                    break;
                case "O":
                    transaction = orderStatusTransaction;
                    break;
                case "S":
                    transaction = stockLevelTransaction;
                    break;
                case "I":
                    transaction = popularItemTransaction;
                    break;
                case "T":
                    transaction = topBalanceTransaction;
                    break;
                case "R":
                    transaction = relatedCustomerTransaction;
                    break;
                default:
                    numQueries--;
                    // throw new Exception("Unknown transaction types");
                    System.err.println("Unknown transaction types");
                    continue;
            }

            lStart = System.nanoTime();
            System.out.println("\n======================================================================");
            // System.out.printf("Transaction ID: %d\n", timeRecord.size());
            System.out.printf("Transaction ID: %d | Type: %s\n", numQueries, transaction.getType());
            try {
                transaction.execute(lines, parameters);
            } catch (Exception ex) {
                System.out.println(ex);
                System.out.println("Transaction Skipped!");
                skippedTransactions++;
            }
            lEnd = System.nanoTime();
            lapse = TimeUnit.MILLISECONDS.convert(lEnd - lStart, TimeUnit.NANOSECONDS);
            calculator.ingestTime(transaction.getType(), lapse);
            System.out.printf("Time taken: %d\n", lapse);
            System.out.println("======================================================================");
        }
        end = System.nanoTime();
        totalLapse = TimeUnit.SECONDS.convert(end - start, TimeUnit.NANOSECONDS);
        System.out.printf("Total Skipped Transactions: %d\n", skippedTransactions);
        calculator.computeTimeStatistics(totalLapse);
        scanner.close();
    }
}
