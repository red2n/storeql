-- Cash payment limits (readiness review 09.17).
--
-- From 10 July 2027 a payment in cash for goods or services of EUR 10,000 or more, or its
-- equivalent in national currency, may neither be made nor accepted anywhere in the Union
-- (Regulation (EU) 2024/1624 art.80(1)); a member state may keep a lower limit, and several have
-- for years — France at EUR 1,000 for a resident, Greece at 500, Spain at 1,000, Belgium and
-- Portugal at 3,000, Italy at 5,000. India refuses cash of two lakh rupees or more (Income-tax Act
-- s.269ST). A till accepted any amount. The limits are jurisdiction data with their citations, in
-- the currency the law names, and a service refuses a cash payment that would reach one where
-- the store trades: the register is asked, never a literal in code.
--
-- from_amount is the amount at and above which cash is refused, as each instrument words it
-- ("EUR 10 000 or more", "égal ou supérieur à 1 000 euros", "two lakh rupees or more"). A country
-- whose currency is not the law's (the EU cap for a Polish or Czech business) needs a row in its
-- own currency before it can be applied: the equivalent is a matter of rate, not of law.
CREATE TABLE cash_limits (
    scope_kind     TEXT           NOT NULL,
    scope          TEXT           NOT NULL,
    currency       CHAR(3)        NOT NULL,
    from_amount    NUMERIC(14, 2) NOT NULL,
    effective_from DATE           NOT NULL,
    effective_to   DATE,
    citation       TEXT           NOT NULL,
    summary        TEXT           NOT NULL,
    CONSTRAINT pk_cash_limits PRIMARY KEY (scope_kind, scope, currency, effective_from),
    CONSTRAINT chk_cash_limit_scope_kind CHECK (scope_kind IN ('REGIME', 'COUNTRY')),
    CONSTRAINT chk_cash_limit_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_cash_limit_amount CHECK (from_amount > 0),
    CONSTRAINT chk_cash_limit_window CHECK (effective_to IS NULL OR effective_to >= effective_from)
);
CREATE INDEX idx_cash_limits_scope ON cash_limits (scope_kind, scope);

INSERT INTO cash_limits (scope_kind, scope, currency, from_amount, effective_from, effective_to, citation, summary) VALUES
 ('REGIME','EU','EUR',10000.00,DATE '2027-07-10',NULL,'Regulation (EU) 2024/1624 art.80(1)',
  'A payment in cash for goods or services of EUR 10,000 or more, or the equivalent in national currency, may neither be made nor accepted.'),
 ('COUNTRY','FR','EUR',1000.00,DATE '2015-09-01',NULL,'Code monétaire et financier art. L112-6 and D112-3',
  'A resident for tax may not pay a business EUR 1,000 or more in cash; a non-resident consumer, EUR 15,000 or more.'),
 ('COUNTRY','BE','EUR',3000.00,DATE '2017-10-16',NULL,'Loi du 18 septembre 2017, art. 67',
  'A payment in cash of EUR 3,000 or more for goods or services is prohibited, whatever the number of instalments.'),
 ('COUNTRY','IT','EUR',5000.00,DATE '2023-01-01',NULL,'D.Lgs. 231/2007 art. 49, as amended by Legge 197/2022',
  'A transfer of cash of EUR 5,000 or more between different parties is prohibited.'),
 ('COUNTRY','ES','EUR',1000.00,DATE '2021-07-11',NULL,'Ley 7/2012 art. 7, as amended by Ley 11/2021',
  'A payment in cash of EUR 1,000 or more is prohibited where either party is a business.'),
 ('COUNTRY','PT','EUR',3000.00,DATE '2017-08-23',NULL,'Lei n.º 92/2017, art. 63.º-E LGT',
  'A payment in cash of EUR 3,000 or more is prohibited; EUR 10,000 or more for a non-resident consumer.'),
 ('COUNTRY','GR','EUR',500.00,DATE '2017-01-01',NULL,'Law 4446/2016 art. 20',
  'A sale of goods or services to a consumer of EUR 500 or more must be paid other than in cash.'),
 ('COUNTRY','IN','INR',200000.00,DATE '2017-04-01',NULL,'Income-tax Act 1961 s.269ST',
  'No person may receive two lakh rupees or more in cash in a day, in one transaction, or for one event or occasion.');
