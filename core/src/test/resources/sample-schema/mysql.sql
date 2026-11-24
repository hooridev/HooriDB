-- The sample schema that tests against real servers read. The scripts of the three providers hold
-- the same tables, view and rows, as far as their SQL allows. It runs once, when the server's
-- container starts, in the database "hoori" as root.

SET NAMES utf8mb4;

CREATE TABLE customer (
  id integer PRIMARY KEY,
  name varchar(100) NOT NULL,
  email varchar(200),
  active boolean NOT NULL,
  born date,
  joined datetime NOT NULL,
  balance decimal(12, 2) NOT NULL,
  notes text
);

CREATE TABLE purchase (
  id integer PRIMARY KEY,
  customer_id integer NOT NULL,
  total decimal(12, 2) NOT NULL,
  bought datetime NOT NULL,
  FOREIGN KEY (customer_id) REFERENCES customer (id)
);

CREATE VIEW active_customer AS SELECT id, name, email FROM customer WHERE active;

-- A primary key of two columns.
CREATE TABLE purchase_line (
  purchase_id integer NOT NULL,
  line integer NOT NULL,
  item varchar(80) NOT NULL,
  quantity decimal(10, 2),
  PRIMARY KEY (purchase_id, line),
  FOREIGN KEY (purchase_id) REFERENCES purchase (id)
);

-- Enough rows to page through.
CREATE TABLE reading (
  id integer PRIMARY KEY,
  label varchar(20) NOT NULL,
  amount decimal(10, 3)
);

CREATE UNIQUE INDEX customer_email_idx ON customer (email);
CREATE INDEX reading_label_amount_idx ON reading (label, amount);

INSERT INTO customer VALUES
  (1, 'Ada Lovelace', 'ada@example.com', true, DATE '1815-12-10', TIMESTAMP '2024-01-15 09:30:00', 120.50, 'First customer'),
  (2, 'Charles Babbage', NULL, false, DATE '1791-12-26', TIMESTAMP '2024-02-01 14:00:00', 0.00, NULL),
  (3, 'Grace Hopper', 'grace@example.com', true, NULL, TIMESTAMP '2024-03-09 08:15:30', -15.25, 'Ünïcödé ✓');

INSERT INTO purchase VALUES
  (1, 1, 19.99, TIMESTAMP '2024-01-16 10:00:00'),
  (2, 1, 5.00, TIMESTAMP '2024-01-20 18:45:00'),
  (3, 3, 249.00, TIMESTAMP '2024-03-10 12:00:00');

INSERT INTO purchase_line VALUES
  (1, 1, 'Notebook', 2.00),
  (1, 2, 'Pencil', NULL),
  (3, 1, 'Calculating engine', 1.00);

INSERT INTO reading
WITH digit (d) AS (
  SELECT 0 UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
  UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9)
SELECT n, CONCAT('reading ', n), CASE WHEN n % 10 = 0 THEN NULL ELSE n / 8.0 END
FROM (
  SELECT a.d + 10 * b.d + 100 * c.d + 1000 * e.d + 1 AS n
  FROM digit a CROSS JOIN digit b CROSS JOIN digit c CROSS JOIN digit e
  WHERE e.d < 5) numbers;

-- Databases whose names a URL must carry intact.
CREATE DATABASE `with space`;
CREATE DATABASE `Grüße`;
GRANT ALL ON `with space`.* TO 'hoori'@'%';
GRANT ALL ON `Grüße`.* TO 'hoori'@'%';
