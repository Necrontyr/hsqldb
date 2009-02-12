#!/usr/bin/php5
<?php

# $Id$

# Sample PHP script accessing HyperSQL through the ODBC extension module.

# This test HyperSQL client uses the ODBC DSN "tstdsn" to connect up to a
# HyperSQL server.  Just configure your own DSN to use the HyperSQL ODBC
# driver, specifying the HyperSQL server host name, database name, user,
# password, etc.

# Author:  Blaine Simpson  (blaine dot simpson at admc dot com)


$conn_id = odbc_connect('tstdsn', '', '');
if (!$conn_id) exit('Connection Failed: ' . $conn_id . "\n");

if (!odbc_autocommit($conn_id, FALSE))
    exit("Failed to turn off AutoCommit mode\n");

if (!odbc_exec($conn_id, "DROP TABLE tsttbl IF EXISTS"))
    exit("DROP command failed\n");

if (!odbc_exec($conn_id,
    "CREATE TABLE tsttbl(\n"
    . "    id BIGINT generated BY DEFAULT AS IDENTITY,\n"
    . "    vc VARCHAR(20),\n"
    . "    entrytime TIMESTAMP DEFAULT current_timestamp NOT NULL\n"
    . ")"))
    exit("CREATE TABLE command failed\n");


# First do a non-parameterized insert
if (!odbc_exec($conn_id, "INSERT INTO tsttbl(id, vc) VALUES(1, 'one')"))
    exit("Insertion of first row failed\n");

# Now parameterized inserts
$stmt = odbc_prepare($conn_id, "INSERT INTO tsttbl(id, vc) VALUES(?, ?)");
if (!$stmt) exit("Preparation of INSERT statement failed \n");

$rv = odbc_execute($stmt, array(2, 'two'));
if ($rv != 1) exit("2nd Insertion failed with  value $rv\n");
$rv = odbc_execute($stmt, array(3, 'three'));
if ($rv != 1) exit("3rd Insertion failed with  value $rv\n");
$rv = odbc_execute($stmt, array(4, 'four'));
if ($rv != 1) exit("4th Insertion failed with  value $rv\n");
$rv = odbc_execute($stmt, array(5, 'five'));
if ($rv != 1) exit("5th Insertion failed with  value $rv\n");
odbc_commit($conn_id);

# A non-parameterized query
$rs = odbc_exec($conn_id, "SELECT * FROM tsttbl WHERE id < 3");
if (!$rs) exit("Error in SQL\n");

$rownum = 0;
while (odbc_fetch_row($rs)) {
    $rownum++;
    echo "$rownum: " .  odbc_result($rs, "id")
        . '|' . odbc_result($rs, "vc")
        . '|' . odbc_result($rs, "entrytime") . "\n";
}

# You need to use the PDO_ODBC extension to parameterize queries (selects).
# If you want an example of this, just ask.

odbc_close($conn_id);

?>
