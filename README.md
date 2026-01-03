# Spark Structured Streaming Lab

## Introduction

This laboratory focuses on Apache Spark Structured Streaming, a powerful stream processing engine built on the Spark SQL engine. The purpose of this lab is to demonstrate how to build real-time data processing applications that can continuously analyze data as it arrives, rather than processing it in batch mode after collection.

The learning objectives of this lab are to understand the fundamentals of stream processing with Spark, to implement various streaming analytics operations such as aggregations and transformations, to work with different data schemas in a streaming context, and to observe how Spark handles incremental data processing when new files are added to a monitored directory. Through this hands-on experience, I gained practical knowledge of building streaming pipelines that monitor HDFS directories for new data files and apply real-time analytics to the incoming order data.

---

## Table of Contents

1. [Project Structure](#project-structure)
2. [Data Files Overview](#data-files-overview)
3. [Code Explanation](#code-explanation)
4. [Environment Setup and Execution](#environment-setup-and-execution)
5. [Testing with Schema V1](#testing-with-schema-v1)
6. [Testing with Schema V2](#testing-with-schema-v2)
7. [How to Run](#how-to-run)
8. [Conclusion](#conclusion)

---

## Project Structure

The project is organized as a standard Maven project with Docker containerization for the Hadoop and Spark infrastructure. The following diagram illustrates the directory structure and the purpose of each component.

```
spark-structured-streaming/
├── data/
│   ├── orders1.csv          # Order data with client names (Schema V1)
│   ├── orders2.csv          # Order data with product IDs (Schema V2)
│   └── orders3.csv          # Additional order data (Schema V2)
├── src/
│   └── main/
│       ├── java/
│       │   └── ma/enset/
│       │       └── Main.java    # Main streaming application
│       └── resources/
│           └── log4j2.properties # Log configuration
├── screenshots/              # Execution screenshots
├── docker-compose.yaml       # Container orchestration
├── pom.xml                   # Maven dependencies
└── config                    # Hadoop configuration
```

---

## Data Files Overview

The lab works with e-commerce order data stored in CSV format. There are two schema versions used to demonstrate the flexibility of the streaming application in handling different data structures.

### Schema V1 (orders1.csv)

This schema includes customer names directly in the order records, which is useful for generating client-focused reports without requiring additional joins.

```csv
order_id,client_id,client_name,product,quantity,price,order_date,status,total
1001,C001,John Smith,Laptop,1,899.99,2025-01-05,Completed,899.99
1002,C002,Sarah Johnson,Wireless Mouse,2,19.99,2025-01-06,Completed,39.98
1003,C001,John Smith,USB-C Adapter,1,12.50,2025-01-08,Cancelled,12.50
1004,C003,Michael Brown,Office Chair,1,149.99,2025-01-12,Completed,149.99
1005,C004,Emma Wilson,Smartphone,1,699.00,2025-01-15,Pending,699.00
...
```

### Schema V2 (orders2.csv, orders3.csv)

This schema includes product identifiers, which is more suitable for inventory management and product-centric analytics.

```csv
order_id,client_id,product_id,product_name,quantity,unit_price,total_amount,order_date,status
1001,C001,P001,Laptop,1,899.99,899.99,2025-01-05,Completed
1002,C002,P005,Wireless Mouse,2,19.99,39.98,2025-01-06,Completed
1003,C001,P010,USB-C Adapter,1,12.5,12.5,2025-01-08,Cancelled
1004,C003,P015,Office Chair,1,149.99,149.99,2025-01-12,Completed
1005,C004,P002,Smartphone,1,699.0,699.0,2025-01-15,Pending
...
```

---

## Code Explanation

The streaming application is implemented in Java and consists of several key components. I will explain each section of the code to provide a clear understanding of how the streaming analytics work.

### Package Declaration and Imports

```java
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
```

The application imports the necessary Spark SQL classes for working with structured data, streaming queries, and data types. The log4j imports are used to configure logging levels, and the static import of functions provides access to aggregation and transformation operations like `sum`, `count`, `avg`, and `filter`.

### Main Class and Log Configuration

```java
public class Main {
    public static void main(String[] args) throws Exception {
        
        // Configure log levels to reduce noise
        Logger.getLogger("org").setLevel(Level.WARN);
        Logger.getLogger("org.apache.spark").setLevel(Level.WARN);
        Logger.getLogger("org.apache.hadoop").setLevel(Level.WARN);
        Logger.getLogger("akka").setLevel(Level.ERROR);
        Logger.getLogger("org.eclipse.jetty").setLevel(Level.ERROR);
        
        // Default to schema version 1, can be overridden via command line
        int schemaVersion = args.length > 0 ? Integer.parseInt(args[0]) : 1;
```

The main method begins by configuring the logging level to suppress informational messages from Spark, Hadoop, and other libraries. This ensures that the console output focuses on the actual analytics results rather than being cluttered with framework messages. The schema version is determined by a command-line argument, defaulting to version 1 if not specified.

### SparkSession Creation

```java
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
```

The SparkSession is the entry point for Spark functionality. I create it with a descriptive application name that includes the schema version for easy identification in the Spark UI. Based on the schema version, the application routes to the appropriate analytics method.

### Schema V1 Definition and Streaming Source

```java
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

        Dataset<Row> ordersDF = spark.readStream()
                .schema(schema)
                .option("header", true)
                .csv("hdfs://namenode:8020/data/orders1/");
```

The schema is defined explicitly using StructType and StructField classes, which allows Spark to correctly parse the CSV data. The readStream method creates a streaming DataFrame that monitors the specified HDFS directory for new CSV files. When a new file appears in this directory, Spark automatically reads and processes it.

### Analytics Case 1: Display Raw Orders

```java
        StreamingQuery rawOrdersQuery = ordersDF
                .writeStream()
                .format("console")
                .outputMode(OutputMode.Append())
                .option("truncate", false)
                .queryName("raw_orders_v1")
                .start();
```

This query displays all incoming orders to the console without any transformation. The Append output mode means only new rows are displayed, not the entire dataset. The truncate option is set to false to show complete field values without cutting them off.

### Analytics Case 2: Total Sales Aggregation

```java
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
```

This aggregation calculates three key metrics across all orders: the sum of all sales, the total number of orders, and the average order value. The Complete output mode is required for aggregations because the entire result table must be output each time there is an update.

### Analytics Case 3: Sales by Product

```java
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
```

This query groups the orders by product name and calculates sales metrics for each product, including total revenue, quantity sold, and order count. The results are sorted in descending order by product sales to highlight the best-performing products.

### Analytics Case 4: Sales by Client

```java
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
```

This analytics case provides customer spending insights by grouping orders by client ID and name. It calculates total spending, order frequency, and average order value for each customer, sorted by total spent in descending order to identify the most valuable customers.

### Analytics Case 5: Order Count by Status

```java
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
```

This query provides operational insights by counting orders in each status category (Completed, Pending, Cancelled) and calculating the total value associated with each status. This is useful for monitoring order fulfillment and identifying potential issues with cancelled or pending orders.

### Analytics Case 6: High-Value Orders Filter

```java
        Dataset<Row> highValueOrdersDF = ordersDF
                .filter(col("total").gt(100))
                .select("order_id", "client_name", "product", "total", "status");
        
        StreamingQuery highValueQuery = highValueOrdersDF
                .writeStream()
                .format("console")
                .outputMode(OutputMode.Append())
                .queryName("high_value_orders_v1")
                .start();
```

This transformation filters the stream to show only orders with a total value greater than 100. This is useful for monitoring significant transactions in real-time. The Append mode is used because this is a filter operation rather than an aggregation.

### Analytics Case 7: Top Products by Quantity Sold

```java
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
```

This final analytics case ranks products by the total quantity sold, providing insights into product popularity regardless of price. The awaitAnyTermination method keeps the application running and processing data until one of the streaming queries terminates.

---

## Environment Setup and Execution

The following section documents the step-by-step process of setting up and running the streaming application.

### Step 1: Starting the Docker Environment

The first step is to start all Docker containers that form the Hadoop and Spark cluster. This includes the HDFS NameNode and DataNode, YARN ResourceManager and NodeManager, and the Spark Master and Worker.

```bash
cd /home/insane.beggar/Master/Big_Data/Labs/spark-structured-streaming
docker compose up -d
```

![Docker Compose Up](screenshots/docker-compose-up.png)

### Step 2: Verifying Running Containers

After starting the containers, I verified that all services were running correctly using the docker ps command.

```bash
docker ps
```

![Docker PS](screenshots/docker-ps.png)

### Step 3: Checking HDFS Safe Mode Status

Before interacting with HDFS, I waited for the NameNode to exit safe mode, which is a startup state where the filesystem is read-only.

```bash
docker exec namenode hdfs dfsadmin -safemode get
```

![Safe Mode Off](screenshots/safe-mode-off.png)

### Step 4: Creating HDFS Directories

I created separate directories in HDFS for each schema version to organize the data files properly.

```bash
docker exec namenode hdfs dfs -mkdir -p /data/orders1
docker exec namenode hdfs dfs -mkdir -p /data/orders2
docker exec namenode hdfs dfs -ls /data
```

![HDFS Directories](screenshots/hdfs-directories-creation-and-check.png)

### Step 5: Copying CSV Files to Container

The CSV data files were copied from the local filesystem into the namenode container.

```bash
docker cp data/orders1.csv namenode:/tmp/orders1.csv
docker cp data/orders2.csv namenode:/tmp/orders2.csv
docker cp data/orders3.csv namenode:/tmp/orders3.csv
```

![Files Copy](screenshots/files-copy.png)

### Step 6: Verifying Files in Container

I verified that the files were successfully copied into the container.

```bash
docker exec namenode sh -c "ls -la /tmp/*.csv"
```

![Verify Files](screenshots/Verify%20Files%20in%20Container.png)

### Step 7: Building the Maven Project

The Java application was compiled and packaged into a JAR file using Maven.

```bash
mvn clean package -DskipTests
```

![Maven Build](screenshots/Build%20the%20Maven%20Project.png)

### Step 8: Verifying JAR Creation

I confirmed that the JAR file was successfully created in the target directory.

```bash
ls -la target/*.jar
```

![JAR Created](screenshots/Verify%20JAR%20was%20Created.png)

### Step 9: Copying JAR to Spark Master

The compiled JAR file was copied to the Spark Master container for execution.

```bash
docker cp target/spark-structured-streaming-1.0-SNAPSHOT.jar spark-master:/opt/spark/
```

![Copy JAR](screenshots/Copy%20JAR%20to%20Spark%20Master.png)

### Step 10: Configuring Spark Logging

To reduce log noise and focus on the analytics output, I created a log4j2 configuration file in the Spark container.

```bash
docker exec spark-master mkdir -p /opt/spark/conf
docker exec spark-master sh -c 'cat > /opt/spark/conf/log4j2.properties << EOF
rootLogger.level = ERROR
rootLogger.appenderRef.stdout.ref = console
appender.console.type = Console
appender.console.name = console
appender.console.layout.type = PatternLayout
appender.console.layout.pattern = %d{HH:mm:ss} %-5level - %msg%n
logger.spark.name = org.apache.spark
logger.spark.level = ERROR
logger.hadoop.name = org.apache.hadoop
logger.hadoop.level = ERROR
logger.jetty.name = org.eclipse.jetty
logger.jetty.level = ERROR
logger.akka.name = akka
logger.akka.level = ERROR
EOF'
```

---

## Testing with Schema V1

### Starting the Streaming Application

I launched the Spark Structured Streaming application with schema version 1 to process orders1.csv format.

```bash
docker exec -it spark-master /opt/spark/bin/spark-submit \
  --master spark://spark-master:7077 \
  --class ma.enset.Main \
  /opt/spark/spark-structured-streaming-1.0-SNAPSHOT.jar 1
```

![Start Streaming V1](screenshots/Start%20Streaming%20Application.png)

### Uploading Data to HDFS

In a separate terminal, I uploaded the orders1.csv file to trigger the streaming processing.

```bash
docker exec namenode hdfs dfs -put /tmp/orders1.csv /data/orders1/
docker exec namenode hdfs dfs -ls /data/orders1/
```

![Upload Orders1](screenshots/Upload%20orders1%20and%20Verify%20File%20in%20HDFS.png)

### Schema V1 Streaming Results

Upon uploading the CSV file, the streaming application immediately processed the data and produced the following analytics results.

**Raw Orders Display:**

The application first displays all 15 orders with their complete details, showing the order ID, client information, product name, quantity, price, order date, status, and total amount.

```
+--------+---------+---------------+--------------+--------+------+----------+---------+------+
|order_id|client_id|client_name    |product       |quantity|price |order_date|status   |total |
+--------+---------+---------------+--------------+--------+------+----------+---------+------+
|1001    |C001     |John Smith     |Laptop        |1       |899.99|2025-01-05|Completed|899.99|
|1002    |C002     |Sarah Johnson  |Wireless Mouse|2       |19.99 |2025-01-06|Completed|39.98 |
|1003    |C001     |John Smith     |USB-C Adapter |1       |12.5  |2025-01-08|Cancelled|12.5  |
|1004    |C003     |Michael Brown  |Office Chair  |1       |149.99|2025-01-12|Completed|149.99|
|1005    |C004     |Emma Wilson    |Smartphone    |1       |699.0 |2025-01-15|Pending  |699.0 |
|1006    |C002     |Sarah Johnson  |Keyboard      |1       |29.99 |2025-01-15|Completed|29.99 |
|1007    |C005     |David Miller   |Monitor 27"   |2       |199.99|2025-01-18|Completed|399.98|
|1008    |C006     |Linda Davis    |Webcam HD     |1       |49.99 |2025-01-18|Completed|49.99 |
|1009    |C003     |Michael Brown  |Desk Lamp     |1       |24.99 |2025-01-19|Completed|24.99 |
|1010    |C007     |Olivia Martin  |Headphones    |1       |89.99 |2025-01-20|Pending  |89.99 |
|1011    |C004     |Emma Wilson    |Tablet        |1       |299.0 |2025-01-21|Completed|299.0 |
|1012    |C008     |James Anderson |Printer       |1       |129.99|2025-01-22|Completed|129.99|
|1013    |C009     |Patricia Thomas|Paper Pack A4 |5       |4.5   |2025-01-23|Completed|22.5  |
|1014    |C010     |Robert Taylor  |Ink Cartridge |2       |22.99 |2025-01-25|Completed|45.98 |
|1015    |C002     |Sarah Johnson  |Laptop Stand  |1       |34.99 |2025-01-26|Pending  |34.99 |
+--------+---------+---------------+--------------+--------+------+----------+---------+------+
```

**High-Value Orders (greater than 100):**

The filter operation identified 6 orders exceeding the 100 threshold, highlighting significant transactions.

```
+--------+--------------+------------+------+---------+
|order_id|   client_name|     product| total|   status|
+--------+--------------+------------+------+---------+
|    1001|    John Smith|      Laptop|899.99|Completed|
|    1004| Michael Brown|Office Chair|149.99|Completed|
|    1005|   Emma Wilson|  Smartphone| 699.0|  Pending|
|    1007|  David Miller| Monitor 27"|399.98|Completed|
|    1011|   Emma Wilson|      Tablet| 299.0|Completed|
|    1012|James Anderson|     Printer|129.99|Completed|
+--------+--------------+------------+------+---------+
```

**Total Sales Aggregation:**

The overall business metrics show total revenue of $2,928.86 from 15 orders with an average order value of $195.26.

```
+-----------------+------------+-----------------+
|      total_sales|total_orders|  avg_order_value|
+-----------------+------------+-----------------+
|2928.859999999999|          15|195.2573333333333|
+-----------------+------------+-----------------+
```

**Orders by Status:**

The status distribution shows that the majority of orders (11) are completed, with 3 pending and 1 cancelled.

```
+---------+-----------+-----------+
|   status|order_count|total_value|
+---------+-----------+-----------+
|Completed|         11|    2092.38|
|Cancelled|          1|       12.5|
|  Pending|          3|     823.98|
+---------+-----------+-----------+
```

**Top Products by Quantity Sold:**

Paper Pack A4 leads in quantity with 5 units sold, followed by items typically purchased in pairs.

```
+--------------+-------------------+
|       product|total_quantity_sold|
+--------------+-------------------+
| Paper Pack A4|                  5|
|Wireless Mouse|                  2|
|   Monitor 27"|                  2|
| Ink Cartridge|                  2|
|  Office Chair|                  1|
| USB-C Adapter|                  1|
|        Laptop|                  1|
|  Laptop Stand|                  1|
|     Desk Lamp|                  1|
|        Tablet|                  1|
|     Webcam HD|                  1|
|       Printer|                  1|
|      Keyboard|                  1|
|    Smartphone|                  1|
|    Headphones|                  1|
+--------------+-------------------+
```

**Sales by Product:**

The revenue breakdown by product shows Laptop as the top revenue generator at $899.99, followed by Smartphone at $699.

```
+--------------+-------------+--------------+-----------+
|       product|product_sales|total_quantity|order_count|
+--------------+-------------+--------------+-----------+
|        Laptop|       899.99|             1|          1|
|    Smartphone|        699.0|             1|          1|
|   Monitor 27"|       399.98|             2|          1|
|        Tablet|        299.0|             1|          1|
|  Office Chair|       149.99|             1|          1|
|       Printer|       129.99|             1|          1|
|    Headphones|        89.99|             1|          1|
|     Webcam HD|        49.99|             1|          1|
| Ink Cartridge|        45.98|             2|          1|
|Wireless Mouse|        39.98|             2|          1|
|  Laptop Stand|        34.99|             1|          1|
|      Keyboard|        29.99|             1|          1|
|     Desk Lamp|        24.99|             1|          1|
| Paper Pack A4|         22.5|             5|          1|
| USB-C Adapter|         12.5|             1|          1|
+--------------+-------------+--------------+-----------+
```

**Sales by Client:**

Customer spending analysis reveals Emma Wilson as the top spender at $998, followed by John Smith at $912.49.

```
+---------+---------------+------------------+-----------+-----------------+
|client_id|    client_name|       total_spent|order_count|  avg_order_value|
+---------+---------------+------------------+-----------+-----------------+
|     C004|    Emma Wilson|             998.0|          2|            499.0|
|     C001|     John Smith|            912.49|          2|          456.245|
|     C005|   David Miller|            399.98|          1|           399.98|
|     C003|  Michael Brown|174.98000000000002|          2|87.49000000000001|
|     C008| James Anderson|            129.99|          1|           129.99|
|     C002|  Sarah Johnson|104.96000000000001|          3|34.98666666666667|
|     C007|  Olivia Martin|             89.99|          1|            89.99|
|     C006|    Linda Davis|             49.99|          1|            49.99|
|     C010|  Robert Taylor|             45.98|          1|            45.98|
|     C009|Patricia Thomas|              22.5|          1|             22.5|
+---------+---------------+------------------+-----------+-----------------+
```

---

## Testing with Schema V2

### Starting the Application with Schema V2

After stopping the first application with Ctrl+C, I launched it again with schema version 2.

```bash
docker exec -it spark-master /opt/spark/bin/spark-submit \
  --master spark://spark-master:7077 \
  --class ma.enset.Main \
  /opt/spark/spark-structured-streaming-1.0-SNAPSHOT.jar 2
```

![Start Streaming V2](screenshots/%20Start%20App%20with%20Schema%20V2.png)

### Uploading orders2.csv and orders3.csv

I uploaded both files sequentially to demonstrate how Spark handles incremental data processing.

```bash
docker exec namenode hdfs dfs -put /tmp/orders2.csv /data/orders2/
docker exec namenode hdfs dfs -put /tmp/orders3.csv /data/orders2/
docker exec namenode hdfs dfs -ls /data/orders2/
```

![Upload Orders2 and 3](screenshots/Upload%20orders2%20and%203%20and%20Verify%20File%20in%20HDFS.png)

### Schema V2 Streaming Results

The streaming application demonstrates incremental processing across two batches. Batch 0 processes orders2.csv, and Batch 1 processes the additional data from orders3.csv.

**Batch 0 - Initial Data Processing (orders2.csv):**

Raw orders display showing 15 orders with product ID information.

```
+--------+---------+----------+--------------+--------+----------+------------+----------+---------+
|order_id|client_id|product_id|product_name  |quantity|unit_price|total_amount|order_date|status   |
+--------+---------+----------+--------------+--------+----------+------------+----------+---------+
|1001    |C001     |P001      |Laptop        |1       |899.99    |899.99      |2025-01-05|Completed|
|1002    |C002     |P005      |Wireless Mouse|2       |19.99     |39.98       |2025-01-06|Completed|
|1003    |C001     |P010      |USB-C Adapter |1       |12.5      |12.5        |2025-01-08|Cancelled|
|1004    |C003     |P015      |Office Chair  |1       |149.99    |149.99      |2025-01-12|Completed|
|1005    |C004     |P002      |Smartphone    |1       |699.0     |699.0       |2025-01-15|Pending  |
|1006    |C002     |P022      |Keyboard      |1       |29.99     |29.99       |2025-01-15|Completed|
|1007    |C005     |P012      |Monitor 27"   |2       |199.99    |399.98      |2025-01-18|Completed|
|1008    |C006     |P020      |Webcam HD     |1       |49.99     |49.99       |2025-01-18|Completed|
|1009    |C003     |P032      |Desk Lamp     |1       |24.99     |24.99       |2025-01-19|Completed|
|1010    |C007     |P009      |Headphones    |1       |89.99     |89.99       |2025-01-20|Pending  |
|1011    |C004     |P003      |Tablet        |1       |299.0     |299.0       |2025-01-21|Completed|
|1012    |C008     |P018      |Printer       |1       |129.99    |129.99      |2025-01-22|Completed|
|1013    |C009     |P007      |Paper Pack A4 |5       |4.5       |22.5        |2025-01-23|Completed|
|1014    |C010     |P025      |Ink Cartridge |2       |22.99     |45.98       |2025-01-25|Completed|
|1015    |C002     |P030      |Laptop Stand  |1       |34.99     |34.99       |2025-01-26|Pending  |
+--------+---------+----------+--------------+--------+----------+------------+----------+---------+
```

Total sales for Batch 0:

```
+-----------------+------------+-----------------+
|      total_sales|total_orders|  avg_order_value|
+-----------------+------------+-----------------+
|2928.859999999999|          15|195.2573333333333|
+-----------------+------------+-----------------+
```

**Batch 1 - Incremental Update (after orders3.csv):**

After adding orders3.csv, the streaming application automatically detects and processes the new data, updating all aggregations in real-time.

Total sales doubled after processing orders3.csv:

```
+-----------------+------------+-----------------+
|      total_sales|total_orders|  avg_order_value|
+-----------------+------------+-----------------+
|5857.719999999998|          30|195.2573333333333|
+-----------------+------------+-----------------+
```

Orders by status updated:

```
+---------+-----------+-----------+
|   status|order_count|total_value|
+---------+-----------+-----------+
|Completed|         22|    4184.76|
|Cancelled|          2|       25.0|
|  Pending|          6|    1647.96|
+---------+-----------+-----------+
```

Client spending updated with doubled values:

```
+---------+------------------+-----------+-----------------+
|client_id|       total_spent|order_count|  avg_order_value|
+---------+------------------+-----------+-----------------+
|     C004|            1996.0|          4|            499.0|
|     C001|           1824.98|          4|          456.245|
|     C005|            799.96|          2|           399.98|
|     C003|349.96000000000004|          4|87.49000000000001|
|     C008|            259.98|          2|           129.99|
|     C002|209.92000000000002|          6|34.98666666666667|
|     C007|            179.98|          2|            89.99|
|     C006|             99.98|          2|            49.99|
|     C010|             91.96|          2|            45.98|
|     C009|              45.0|          2|             22.5|
+---------+------------------+-----------+-----------------+
```

Product sales updated with combined data:

```
+----------+--------------+-------------+--------------+-----------+--------------+
|product_id|  product_name|product_sales|total_quantity|order_count|avg_unit_price|
+----------+--------------+-------------+--------------+-----------+--------------+
|      P001|        Laptop|      1799.98|             2|          2|        899.99|
|      P002|    Smartphone|       1398.0|             2|          2|         699.0|
|      P003|        Tablet|        598.0|             2|          2|         299.0|
|      P012|   Monitor 27"|       399.98|             2|          1|        199.99|
|      P015|  Office Chair|       299.98|             2|          2|        149.99|
|      P018|       Printer|       259.98|             2|          2|        129.99|
|      P009|    Headphones|       179.98|             2|          2|         89.99|
|      P020|     Webcam HD|        99.98|             2|          2|         49.99|
|      P025| Ink Cartridge|        91.96|             4|          2|         22.99|
|      P005|Wireless Mouse|        79.96|             4|          2|         19.99|
|      P030|  Laptop Stand|        69.98|             2|          2|         34.99|
|      P022|      Keyboard|        59.98|             2|          2|         29.99|
|      P032|     Desk Lamp|        49.98|             2|          2|         24.99|
|      P007| Paper Pack A4|         45.0|            10|          2|           4.5|
|      P010| USB-C Adapter|         25.0|             2|          2|          12.5|
+----------+--------------+-------------+--------------+-----------+--------------+
```

---

## How to Run

To execute this project on your own machine, follow these steps in order.

First, ensure that Docker and Docker Compose are installed on your system, along with Java 11 and Maven. Clone or download the project files to your local machine.

Start the Docker environment by navigating to the project directory and running the docker compose command to bring up all containers in detached mode.

Wait approximately 30 seconds for the HDFS NameNode to exit safe mode, then verify by executing the safemode get command on the namenode container.

Create the required HDFS directories for both schema versions by running the mkdir commands for /data/orders1 and /data/orders2.

Copy the CSV data files from the local data directory to the namenode container's /tmp directory using docker cp.

Build the Maven project with mvn clean package to compile the Java code and create the JAR file.

Copy the generated JAR file to the Spark Master container for execution.

Configure logging by creating the log4j2.properties file in the Spark configuration directory to suppress verbose framework logs.

Submit the Spark application using spark-submit with the appropriate schema version (1 or 2) as a command-line argument.

In a separate terminal, upload CSV files to the corresponding HDFS directory to trigger the streaming processing.

Observe the console output showing real-time analytics as data is processed.

---

## Conclusion

This laboratory successfully demonstrated the capabilities of Apache Spark Structured Streaming for real-time data analytics. Through the implementation of seven distinct analytics cases, including aggregations by product, client, and status, as well as filtering operations for high-value orders, I gained practical experience with streaming data processing concepts.

The key takeaways from this lab include understanding how Spark monitors directories for new data files and automatically processes them, learning the difference between Append and Complete output modes for different types of operations, and observing how aggregations are incrementally updated when new data arrives. The transition from Batch 0 to Batch 1 in Schema V2 testing clearly illustrated how Spark maintains state and updates running aggregations in real-time.

The use of Docker containers simplified the infrastructure setup, allowing focus on the streaming application logic rather than cluster configuration. The explicit schema definition approach provided type safety and clear documentation of the expected data format. Overall, this lab provided valuable hands-on experience with modern stream processing techniques that are widely used in production big data systems.
