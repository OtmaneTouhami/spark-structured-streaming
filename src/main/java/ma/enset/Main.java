package ma.enset;

import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.streaming.OutputMode;
import org.apache.spark.sql.streaming.StreamingQuery;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.Metadata;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import static org.apache.spark.sql.functions.*;

/**
 * Spark Structured Streaming Application
 * Demonstrates various streaming analytics on order data from HDFS
 * 
 * Supports TWO CSV schemas:
 * - Schema 1 (orders1.csv): order_id, client_id, client_name, product, quantity, price, order_date, status, total
 * - Schema 2 (orders2/3.csv): order_id, client_id, product_id, product_name, quantity, unit_price, total_amount, order_date, status
 * 
 * Usage:
 *   spark-submit --class ma.enset.Main app.jar [schema_version]
 *   schema_version: 1 for orders1.csv, 2 for orders2/orders3.csv (default: 1)
 */
public class Main {
    public static void main(String[] args) throws Exception {
        
        // ============================================================
        // CONFIGURE LOG LEVELS - Reduce noise, show only WARN/ERROR
        // ============================================================
        Logger.getLogger("org").setLevel(Level.WARN);
        Logger.getLogger("org.apache.spark").setLevel(Level.WARN);
        Logger.getLogger("org.apache.hadoop").setLevel(Level.WARN);
        Logger.getLogger("akka").setLevel(Level.ERROR);
        Logger.getLogger("org.eclipse.jetty").setLevel(Level.ERROR);
        
        // Default to schema version 1 (orders1.csv), can be overridden via command line
        int schemaVersion = args.length > 0 ? Integer.parseInt(args[0]) : 1;
        
        // Create SparkSession
        SparkSession spark = SparkSession.builder()
                .appName("Spark Structured Streaming - Order Analytics (Schema v" + schemaVersion + ")")
                .getOrCreate();
        
        System.out.println("=======================================================");
        System.out.println("Starting Spark Structured Streaming with Schema Version: " + schemaVersion);
        System.out.println("=======================================================");

        if (schemaVersion == 1) {
            runSchemaV1Analytics(spark);
        } else {
            runSchemaV2Analytics(spark);
        }
    }

    /**
     * Schema V1: orders1.csv
     * Columns: order_id, client_id, client_name, product, quantity, price, order_date, status, total
     */
    private static void runSchemaV1Analytics(SparkSession spark) throws Exception {
        System.out.println("Using Schema V1 (orders1.csv format)");
        System.out.println("Reading from: hdfs://namenode:8020/data/orders1/");
        
        StructType schema = new StructType(new StructField[]{
                new StructField("order_id", DataTypes.LongType, true, Metadata.empty()),
                new StructField("client_id", DataTypes.StringType, true, Metadata.empty()),
                new StructField("client_name", DataTypes.StringType, true, Metadata.empty()),
                new StructField("product", DataTypes.StringType, true, Metadata.empty()),
                new StructField("quantity", DataTypes.IntegerType, true, Metadata.empty()),
                new StructField("price", DataTypes.DoubleType, true, Metadata.empty()),
                new StructField("order_date", DataTypes.StringType, true, Metadata.empty()),
                new StructField("status", DataTypes.StringType, true, Metadata.empty()),
                new StructField("total", DataTypes.DoubleType, true, Metadata.empty())
        });

        // Read streaming data from HDFS directory for orders1 format
        Dataset<Row> ordersDF = spark.readStream()
                .schema(schema)
                .option("header", true)
                .csv("hdfs://namenode:8020/data/orders1/");

        // ============================================================
        // ANALYTICS CASE 1: Display Raw Orders
        // ============================================================
        StreamingQuery rawOrdersQuery = ordersDF
                .writeStream()
                .format("console")
                .outputMode(OutputMode.Append())
                .option("truncate", false)
                .queryName("raw_orders_v1")
                .start();

        // ============================================================
        // ANALYTICS CASE 2: Total Sales Aggregation
        // ============================================================
        Dataset<Row> totalSalesDF = ordersDF
                .agg(
                        sum("total").alias("total_sales"),
                        count("order_id").alias("total_orders"),
                        avg("total").alias("avg_order_value")
                );
        
        StreamingQuery totalSalesQuery = totalSalesDF
                .writeStream()
                .format("console")
                .outputMode(OutputMode.Complete())
                .queryName("total_sales_v1")
                .start();

        // ============================================================
        // ANALYTICS CASE 3: Sales by Product
        // ============================================================
        Dataset<Row> salesByProductDF = ordersDF
                .groupBy("product")
                .agg(
                        sum("total").alias("product_sales"),
                        sum("quantity").alias("total_quantity"),
                        count("order_id").alias("order_count")
                )
                .orderBy(desc("product_sales"));
        
        StreamingQuery salesByProductQuery = salesByProductDF
                .writeStream()
                .format("console")
                .outputMode(OutputMode.Complete())
                .queryName("sales_by_product_v1")
                .start();

        // ============================================================
        // ANALYTICS CASE 4: Sales by Client (with client name)
        // ============================================================
        Dataset<Row> salesByClientDF = ordersDF
                .groupBy("client_id", "client_name")
                .agg(
                        sum("total").alias("total_spent"),
                        count("order_id").alias("order_count"),
                        avg("total").alias("avg_order_value")
                )
                .orderBy(desc("total_spent"));
        
        StreamingQuery salesByClientQuery = salesByClientDF
                .writeStream()
                .format("console")
                .outputMode(OutputMode.Complete())
                .queryName("sales_by_client_v1")
                .start();

        // ============================================================
        // ANALYTICS CASE 5: Order Count by Status
        // ============================================================
        Dataset<Row> ordersByStatusDF = ordersDF
                .groupBy("status")
                .agg(
                        count("order_id").alias("order_count"),
                        sum("total").alias("total_value")
                );
        
        StreamingQuery ordersByStatusQuery = ordersByStatusDF
                .writeStream()
                .format("console")
                .outputMode(OutputMode.Complete())
                .queryName("orders_by_status_v1")
                .start();

        // ============================================================
        // ANALYTICS CASE 6: High-Value Orders (> 100)
        // ============================================================
        Dataset<Row> highValueOrdersDF = ordersDF
                .filter(col("total").gt(100))
                .select("order_id", "client_name", "product", "total", "status");
        
        StreamingQuery highValueQuery = highValueOrdersDF
                .writeStream()
                .format("console")
                .outputMode(OutputMode.Append())
                .queryName("high_value_orders_v1")
                .start();

        // ============================================================
        // ANALYTICS CASE 7: Top Products by Quantity Sold
        // ============================================================
        Dataset<Row> topProductsDF = ordersDF
                .groupBy("product")
                .agg(sum("quantity").alias("total_quantity_sold"))
                .orderBy(desc("total_quantity_sold"));
        
        StreamingQuery topProductsQuery = topProductsDF
                .writeStream()
                .format("console")
                .outputMode(OutputMode.Complete())
                .queryName("top_products_v1")
                .start();

        spark.streams().awaitAnyTermination();
    }

    /**
     * Schema V2: orders2.csv, orders3.csv
     * Columns: order_id, client_id, product_id, product_name, quantity, unit_price, total_amount, order_date, status
     */
    private static void runSchemaV2Analytics(SparkSession spark) throws Exception {
        System.out.println("Using Schema V2 (orders2/orders3.csv format)");
        System.out.println("Reading from: hdfs://namenode:8020/data/orders2/");
        
        StructType schema = new StructType(new StructField[]{
                new StructField("order_id", DataTypes.LongType, true, Metadata.empty()),
                new StructField("client_id", DataTypes.StringType, true, Metadata.empty()),
                new StructField("product_id", DataTypes.StringType, true, Metadata.empty()),
                new StructField("product_name", DataTypes.StringType, true, Metadata.empty()),
                new StructField("quantity", DataTypes.IntegerType, true, Metadata.empty()),
                new StructField("unit_price", DataTypes.DoubleType, true, Metadata.empty()),
                new StructField("total_amount", DataTypes.DoubleType, true, Metadata.empty()),
                new StructField("order_date", DataTypes.StringType, true, Metadata.empty()),
                new StructField("status", DataTypes.StringType, true, Metadata.empty())
        });

        // Read streaming data from HDFS directory for orders2/3 format
        Dataset<Row> ordersDF = spark.readStream()
                .schema(schema)
                .option("header", true)
                .csv("hdfs://namenode:8020/data/orders2/");

        // ============================================================
        // ANALYTICS CASE 1: Display Raw Orders
        // ============================================================
        StreamingQuery rawOrdersQuery = ordersDF
                .writeStream()
                .format("console")
                .outputMode(OutputMode.Append())
                .option("truncate", false)
                .queryName("raw_orders_v2")
                .start();

        // ============================================================
        // ANALYTICS CASE 2: Total Sales Aggregation
        // ============================================================
        Dataset<Row> totalSalesDF = ordersDF
                .agg(
                        sum("total_amount").alias("total_sales"),
                        count("order_id").alias("total_orders"),
                        avg("total_amount").alias("avg_order_value")
                );
        
        StreamingQuery totalSalesQuery = totalSalesDF
                .writeStream()
                .format("console")
                .outputMode(OutputMode.Complete())
                .queryName("total_sales_v2")
                .start();

        // ============================================================
        // ANALYTICS CASE 3: Sales by Product (with product_id)
        // ============================================================
        Dataset<Row> salesByProductDF = ordersDF
                .groupBy("product_id", "product_name")
                .agg(
                        sum("total_amount").alias("product_sales"),
                        sum("quantity").alias("total_quantity"),
                        count("order_id").alias("order_count"),
                        avg("unit_price").alias("avg_unit_price")
                )
                .orderBy(desc("product_sales"));
        
        StreamingQuery salesByProductQuery = salesByProductDF
                .writeStream()
                .format("console")
                .outputMode(OutputMode.Complete())
                .queryName("sales_by_product_v2")
                .start();

        // ============================================================
        // ANALYTICS CASE 4: Sales by Client
        // ============================================================
        Dataset<Row> salesByClientDF = ordersDF
                .groupBy("client_id")
                .agg(
                        sum("total_amount").alias("total_spent"),
                        count("order_id").alias("order_count"),
                        avg("total_amount").alias("avg_order_value")
                )
                .orderBy(desc("total_spent"));
        
        StreamingQuery salesByClientQuery = salesByClientDF
                .writeStream()
                .format("console")
                .outputMode(OutputMode.Complete())
                .queryName("sales_by_client_v2")
                .start();

        // ============================================================
        // ANALYTICS CASE 5: Order Count by Status
        // ============================================================
        Dataset<Row> ordersByStatusDF = ordersDF
                .groupBy("status")
                .agg(
                        count("order_id").alias("order_count"),
                        sum("total_amount").alias("total_value")
                );
        
        StreamingQuery ordersByStatusQuery = ordersByStatusDF
                .writeStream()
                .format("console")
                .outputMode(OutputMode.Complete())
                .queryName("orders_by_status_v2")
                .start();

        // ============================================================
        // ANALYTICS CASE 6: High-Value Orders (> 100)
        // ============================================================
        Dataset<Row> highValueOrdersDF = ordersDF
                .filter(col("total_amount").gt(100))
                .select("order_id", "client_id", "product_name", "total_amount", "status");
        
        StreamingQuery highValueQuery = highValueOrdersDF
                .writeStream()
                .format("console")
                .outputMode(OutputMode.Append())
                .queryName("high_value_orders_v2")
                .start();

        // ============================================================
        // ANALYTICS CASE 7: Top Products by Quantity Sold
        // ============================================================
        Dataset<Row> topProductsDF = ordersDF
                .groupBy("product_id", "product_name")
                .agg(sum("quantity").alias("total_quantity_sold"))
                .orderBy(desc("total_quantity_sold"));
        
        StreamingQuery topProductsQuery = topProductsDF
                .writeStream()
                .format("console")
                .outputMode(OutputMode.Complete())
                .queryName("top_products_v2")
                .start();

        spark.streams().awaitAnyTermination();
    }
}