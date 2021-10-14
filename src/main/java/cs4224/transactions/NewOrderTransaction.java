package cs4224.transactions;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.checkerframework.checker.units.qual.A;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class NewOrderTransaction extends BaseTransaction {
    private static int nullCarrierId = -1;
    private int customerId;
    private int warehouseId;
    private int districtId;
    private int noOfItems;
    PreparedStatement getDNextOidQuery;
    PreparedStatement incrementDNextOidQuery;
    PreparedStatement createOrderQuery;
    PreparedStatement getStockInfoQuery;
    PreparedStatement updateStockQuery;
    PreparedStatement getItemInfoQuery;
    PreparedStatement createOrderLineQuery;
    PreparedStatement getWarehouseInfoQuery;
    PreparedStatement getCustomerInfoQuery;

    @RequiredArgsConstructor
    @Accessors(fluent = true) @Getter
    private class NewOrderLine {
        private final int itemId;
        private final int supplierWarehouseId;
        private final int quantity;
    }

    @RequiredArgsConstructor
    @Accessors(fluent = true) @Getter
    private class UpdateStockResult {
        private final BigDecimal originalQuantity;
        private final boolean isSuccessful;
    }

    @RequiredArgsConstructor
    @Accessors(fluent = true) @Getter
    private class ItemResultInfo {
        private final int itemId;
        private final String itemName;
        private final int supplierWarehouseId;
        private final int orderQuantity;
        private final BigDecimal amount;
        private final BigDecimal originalStockQuantity;
    }

    @RequiredArgsConstructor
    @Accessors(fluent = true) @Getter
    private class CustomerInfo {
        private final String lastName;
        private final String credit;
        private final BigDecimal discount;
    }

    @RequiredArgsConstructor
    @Accessors(fluent = true) @Getter
    private class DistrictInfo {
        private final Integer nextOid;
        private final BigDecimal tax;
    }

    @RequiredArgsConstructor
    @Accessors(fluent = true) @Getter
    private class NewOrderSummary {
        private final CustomerInfo customerInfo;
        private final BigDecimal warehouseTax;
        private final BigDecimal districtTax;
        private final Instant oEntryD;
        private final int oid;
        private final BigDecimal totalAmount;
        private final List<ItemResultInfo> itemResultInfoList;
    }

    public NewOrderTransaction(CqlSession session) {
        super(session);

        getDNextOidQuery = session.prepare(
                "SELECT D_TAX, D_NEXT_O_ID " +
                        "FROM DISTRICT " +
                        "WHERE D_W_ID = :d_w_id AND D_ID = :d_id"
        );

        incrementDNextOidQuery = session.prepare(
                "UPDATE DISTRICT " +
                        "SET D_NEXT_O_ID = :d_new_o_id " +
                        "WHERE D_W_ID = :d_w_id AND D_ID = :d_id " +
                        "IF D_NEXT_O_ID = :d_next_o_id"
        );

        createOrderQuery = session.prepare(
                "INSERT INTO ORDERS (O_ID, O_D_ID, O_W_ID, O_C_ID, O_ENTRY_D, O_CARRIER_ID, O_OL_CNT, O_ALL_LOCAL) " +
                        "VALUES (:o_id, :o_d_id, :o_w_id, :o_c_id, :o_entry_d, :o_carrier_id, :o_ol_cnt, :o_all_local)"
        );

        getStockInfoQuery = session.prepare(
                "SELECT S_QUANTITY, S_YTD, S_ORDER_CNT, S_REMOTE_CNT " +
                        "FROM STOCK " +
                        "WHERE S_W_ID = :s_w_id AND S_I_ID = :s_i_id"
        );

        updateStockQuery = session.prepare(
                "UPDATE STOCK " +
                        "SET S_QUANTITY = :s_quantity, S_YTD = :s_ytd, S_ORDER_CNT = :new_s_order_cnt, S_REMOTE_CNT = :s_remote_cnt " +
                        "WHERE S_W_ID = :s_w_id AND S_I_ID = :s_i_id " +
                        "IF S_ORDER_CNT = :original_s_order_cnt"
        );

        getItemInfoQuery = session.prepare(
                "SELECT I_PRICE, I_NAME " +
                        "FROM ITEM " +
                        "WHERE I_ID = :i_id"
        );

        createOrderLineQuery = session.prepare(
                "INSERT INTO ORDER_LINE (OL_W_ID, OL_D_ID, OL_O_ID, OL_NUMBER, OL_I_ID, OL_SUPPLY_W_ID, " +
                        "OL_QUANTITY, OL_AMOUNT, OL_DELIVERY_D, OL_DIST_INFO) " +
                        "VALUES (:ol_w_id, :ol_d_id, :ol_o_id, :ol_number, :ol_i_id, :ol_supply_w_id, " +
                        ":ol_quantity, : ol_amount, :ol_delivery_d, :ol_dist_info)"
        );

        getWarehouseInfoQuery = session.prepare(
                "SELECT W_TAX " +
                        "FROM WAREHOUSE " +
                        "WHERE W_ID = :w_id"
        );

        getCustomerInfoQuery = session.prepare(
                "SELECT C_LAST, C_CREDIT, C_DISCOUNT " +
                        "FROM CUSTOMER " +
                        "WHERE C_W_ID = :c_w_id AND C_D_ID = :c_d_id AND C_ID = :c_id"
        );
    }

    @Override
    public void execute(String[] dataLines, String[] parameters) {
        customerId = Integer.parseInt(parameters[1]);
        warehouseId = Integer.parseInt(parameters[2]);
        districtId = Integer.parseInt(parameters[3]);
        noOfItems = Integer.parseInt(parameters[4]);

        DistrictInfo nextOidResult = getAndUpdateDistrictNextOid();
        Integer orderId = nextOidResult.nextOid;

        List<NewOrderLine> newOrderLines = parseNewOrderLines(dataLines);
        Instant now = Instant.now();
        createNewOrder(orderId, newOrderLines, now);

        List<ItemResultInfo> orderLinesResult = IntStream.range(0, newOrderLines.size())
                .parallel()
                .mapToObj(i -> processNewOrderLine(newOrderLines.get(i), orderId, i+1))
                .collect(Collectors.toList());

        BigDecimal districtTax = nextOidResult.tax;
        BigDecimal warehouseTax = getWarehouseTax();

        CustomerInfo customerInfo = getCustomerInfo();

        BigDecimal totalTax = new BigDecimal(1).add(districtTax).add(warehouseTax);
        BigDecimal percentAfterDiscount = new BigDecimal(1).subtract(customerInfo.discount);

        BigDecimal totalAmount = orderLinesResult.stream()
                .map(result -> result.amount)
                .reduce(new BigDecimal(0), BigDecimal::add)
                .multiply(totalTax)
                .multiply(percentAfterDiscount);

        printSummary(new NewOrderSummary(customerInfo, warehouseTax, districtTax, now, orderId, totalAmount, orderLinesResult));
    }

    private DistrictInfo getAndUpdateDistrictNextOid() {
        boolean isIncrementSuccessful = false;
        int dNextOid = -1;
        BigDecimal dTax = new BigDecimal(-1);

        while (!isIncrementSuccessful) {
            ResultSet resultSet = session.execute(getDNextOidQuery.bind()
                    .setInt("d_w_id", warehouseId)
                    .setInt("d_id", districtId));
            Row row = resultSet.one();

            dTax = row.getBigDecimal("D_TAX");
            dNextOid = row.getInt("D_NEXT_O_ID");

            ResultSet updateRow = session.execute(incrementDNextOidQuery.bind()
                    .setInt("d_w_id", warehouseId)
                    .setInt("d_id", districtId)
                    .setInt("d_next_o_id", dNextOid)
                    .setInt("d_new_o_id", dNextOid + 1));

            isIncrementSuccessful = updateRow.wasApplied();
        }

        return new DistrictInfo(dNextOid, dTax);
    }

    private void createNewOrder(int oid, List<NewOrderLine> newOrderLines, Instant now) {
        boolean isAllItemsLocal = isAllItemsLocal(newOrderLines);
        session.execute(createOrderQuery.bind()
                .setInt("o_id", oid)
                .setInt("o_d_id", districtId)
                .setInt("o_w_id", warehouseId)
                .setInt("o_c_id", customerId)
                .setInstant("o_entry_d", now)
                .setInt("o_carrier_id", nullCarrierId)
                .setBigDecimal("o_ol_cnt", new BigDecimal(noOfItems))
                .setBigDecimal("o_all_local", isAllItemsLocal ? new BigDecimal(1) : new BigDecimal(0)));
    }

    private List<NewOrderLine> parseNewOrderLines(String[] datalines) {
        return Arrays.stream(datalines)
                .map(s -> s.split(","))
                .map(tokens -> new NewOrderLine(Integer.parseInt(tokens[0]), Integer.parseInt(tokens[1]),
                        Integer.parseInt(tokens[2])))
                .collect(Collectors.toList());
    }

    private boolean isAllItemsLocal(List<NewOrderLine> orderLines) {
        return orderLines.stream().allMatch(ol -> ol.supplierWarehouseId == warehouseId);
    }

    private ItemResultInfo processNewOrderLine(NewOrderLine newOrderLine, int orderId, int orderLineNumber) {
        UpdateStockResult updateStockResult = new UpdateStockResult(new BigDecimal(0), false);
        while (!updateStockResult.isSuccessful) {
            updateStockResult = updateStock(newOrderLine);
        }
        return createNewOrderLine(newOrderLine, orderId, orderLineNumber, updateStockResult.originalQuantity);
    }

    private UpdateStockResult updateStock(NewOrderLine newOrderLine) {
         Row currentStockInfo = session.execute(getStockInfoQuery.bind()
                 .setInt("s_w_id", newOrderLine.supplierWarehouseId)
                 .setInt("s_i_id", newOrderLine.itemId))
                 .one();

         BigDecimal originalQty = currentStockInfo.getBigDecimal("S_QUANTITY");
         BigDecimal adjustedQty = originalQty.subtract(new BigDecimal(newOrderLine.quantity));
         if (adjustedQty.compareTo(new BigDecimal(10)) < 0) {
             adjustedQty.add(new BigDecimal(100));
         }

         boolean isSuccessful = session.execute(updateStockQuery.bind()
                 .setBigDecimal("s_quantity", adjustedQty)
                 .setBigDecimal("s_ytd",
                         currentStockInfo.getBigDecimal("S_YTD").add(new BigDecimal(newOrderLine.quantity)))
                 .setInt("new_s_order_cnt", currentStockInfo.getInt("S_ORDER_CNT") + 1)
                 .setInt("s_remote_cnt", newOrderLine.supplierWarehouseId != warehouseId
                         ? currentStockInfo.getInt("S_REMOTE_CNT") + 1
                         : currentStockInfo.getInt("S_REMOTE_CNT"))
                 .setInt("s_w_id", newOrderLine.supplierWarehouseId)
                 .setInt("s_i_id", newOrderLine.itemId)
                 .setInt("original_s_order_cnt", currentStockInfo.getInt("S_ORDER_CNT"))
         ).wasApplied();

         return new UpdateStockResult(originalQty, isSuccessful);
    }

    private ItemResultInfo createNewOrderLine(NewOrderLine newOrderLine, int orderId, int orderLineNumber,
                                              BigDecimal originalStockQuantity) {
        Row itemInfo = session.execute(getItemInfoQuery.bind()
                .setInt("i_id", newOrderLine.itemId))
                .one();

        BigDecimal itemAmount = new BigDecimal(newOrderLine.quantity).multiply(itemInfo.getBigDecimal("I_PRICE"));

        session.execute(createOrderLineQuery.bind()
                .setInt("ol_w_id", warehouseId)
                .setInt("ol_d_id", districtId)
                .setInt("ol_o_id", orderId)
                .setInt("ol_number", orderLineNumber)
                .setInt("ol_i_id", newOrderLine.itemId)
                .setInt("ol_supply_w_id", newOrderLine.supplierWarehouseId)
                .setBigDecimal("ol_quantity", new BigDecimal(newOrderLine.quantity))
                .setBigDecimal("ol_amount", itemAmount)
                .setInstant("ol_delivery_d", null)
                .setString("ol_dist_info", String.format("S_DIST_%d", districtId))
        );

        return new ItemResultInfo(
                newOrderLine.itemId,
                itemInfo.getString("I_NAME"),
                newOrderLine.supplierWarehouseId,
                newOrderLine.quantity,
                itemAmount,
                originalStockQuantity
        );
    }

    private BigDecimal getWarehouseTax() {
        return session.execute(getWarehouseInfoQuery.bind()
                .setInt("w_id", warehouseId))
                .one()
                .getBigDecimal("W_TAX");
    }

    private CustomerInfo getCustomerInfo() {
        Row row = session.execute(getCustomerInfoQuery.bind()
                .setInt("c_w_id", warehouseId)
                .setInt("c_d_id", districtId)
                .setInt("c_id", customerId))
                .one();

        return new CustomerInfo(
                row.getString("C_LAST"),
                row.getString("C_CREDIT"),
                row.getBigDecimal("C_DISCOUNT"));
    }

    private void printSummary(NewOrderSummary summary) {
        DateTimeFormatter formatter = DateTimeFormatter.ofLocalizedDateTime( FormatStyle.SHORT )
                .withLocale( Locale.UK )
                .withZone( ZoneId.of("UTC+08:00") );

        System.out.printf("Customer Info => id: (%d, %d, %d); lastname: %s; credit: %s; discount: %s\n",
                warehouseId, districtId, customerId, summary.customerInfo.lastName, summary.customerInfo.credit,
                summary.customerInfo.discount);
        System.out.printf("Warehouse Info => tax rate: %s\n", summary.warehouseTax);
        System.out.printf("District Info => tax rate: %s\n", summary.districtTax);
        System.out.printf("Order Info => order number: %d, entry date (SG time): %s\n", summary.oid,
                formatter.format(summary.oEntryD()));
        System.out.printf("Items Info => num items: %d, total amount: %s\n", noOfItems, summary.totalAmount);
        System.out.printf("Item details =>\n");
        IntStream.range(0, summary.itemResultInfoList.size())
                .forEach(i -> {
                    ItemResultInfo info = summary.itemResultInfoList.get(i);
                    System.out.printf("%d. item number: %d, item name: %s, supplier warehouse id: %d, " +
                            "quantity: %d, ol_amount: %s, s_quantity: %s\n",
                            i, info.itemId, info.itemName, info.supplierWarehouseId, info.orderQuantity, info.amount,
                            info.orderQuantity);
                });
    }

}