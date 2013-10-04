CREATE TABLE dest_j1(key STRING, value STRING, val2 STRING) STORED AS TEXTFILE;

set hive.auto.convert.join=true;
set hive.auto.convert.join.noconditionaltask=true;
set hive.auto.convert.join.noconditionaltask.size=10000;

-- Since the inputs are small, it should be automatically converted to mapjoin

EXPLAIN EXTENDED
INSERT OVERWRITE TABLE dest_j1
SELECT x.key, z.value, y.value
FROM src1 x JOIN src y ON ((x.key = y.key) and (x.value = y.value)) 
JOIN srcpart z ON (x.value = z.value);

INSERT OVERWRITE TABLE dest_j1
SELECT x.key, z.value, y.value
FROM src1 x JOIN src y ON ((x.key = y.key) and (x.value = y.value)) 
JOIN srcpart z ON (x.value = z.value);

select * from dest_j1 x order by x.key;



