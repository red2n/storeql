-- Jurisdiction rules as reference data: which laws bind a business, from when, and under which
-- instrument.
--
-- Much of what the readiness review found missing is law in some markets and not in others: the
-- 30-day prior price and GPSR are EU law, unit pricing is both EU and UK law, the tobacco birth-date
-- ban is UK law, the DPDP Act is Indian, the e-invoicing mandates arrive country by country. Without a shared answer,
-- each fix would carry its own list of EU members and its own dates — the same kind of literal SJ-D53
-- removed for currencies. tenant-svc owns the tenant's country, so it owns this too, and every
-- service asks it.
--
-- Platform reference data, not tenant data: there is no tenant_id, and nothing here is editable
-- through the API. A change in the law is a migration with its citation.

CREATE TABLE jurisdiction_regimes (
    code     TEXT PRIMARY KEY,
    name     TEXT NOT NULL,
    citation TEXT NOT NULL,
    CONSTRAINT chk_regime_code CHECK (code ~ '^[A-Z]{2,8}$')
);

-- Membership has dates because it changes: the United Kingdom was a member until 31 January 2020,
-- and an obligation that took effect after that does not reach a British business through the EU.
CREATE TABLE jurisdiction_members (
    regime_code TEXT    NOT NULL REFERENCES jurisdiction_regimes (code),
    country     CHAR(2) NOT NULL,
    member_from DATE    NOT NULL,
    member_to   DATE,
    CONSTRAINT pk_jurisdiction_members PRIMARY KEY (regime_code, country, member_from),
    CONSTRAINT chk_member_country CHECK (country ~ '^[A-Z]{2}$'),
    CONSTRAINT chk_member_window CHECK (member_to IS NULL OR member_to >= member_from)
);
CREATE INDEX idx_jurisdiction_members_country ON jurisdiction_members (country);

-- One row per obligation per scope: a regime (every member while a member) or a single country.
--
-- An obligation can carry a number: a period, a minimum validity, a share, a count. limit_value and
-- limit_unit come together or not at all: a number without its unit is unreadable. qualifier narrows a
-- row to one kind of case (a channel, a product class) and is free of any vocabulary here; the service
-- that reads the obligation code owns the meaning. A row with no number has limit_value and
-- limit_unit both null. Nothing ties qualifier to a number: a row with no number may carry one.
CREATE TABLE legal_obligations (
    code           TEXT NOT NULL,
    scope_kind     TEXT NOT NULL,
    scope          TEXT NOT NULL,
    effective_from DATE NOT NULL,
    effective_to   DATE,
    citation       TEXT NOT NULL,
    summary        TEXT NOT NULL,
    limit_value    NUMERIC(18,4),
    limit_unit     TEXT,
    qualifier      TEXT,
    CONSTRAINT pk_legal_obligations PRIMARY KEY (code, scope),
    CONSTRAINT chk_obligation_code CHECK (code ~ '^[A-Z][A-Z0-9_]*$'),
    CONSTRAINT chk_obligation_scope_kind CHECK (scope_kind IN ('REGIME', 'COUNTRY')),
    CONSTRAINT chk_obligation_window CHECK (effective_to IS NULL OR effective_to >= effective_from),
    CONSTRAINT chk_obligation_limit_pair CHECK ((limit_value IS NULL) = (limit_unit IS NULL)),
    CONSTRAINT chk_obligation_limit_value CHECK (limit_value IS NULL OR limit_value >= 0),
    CONSTRAINT chk_obligation_limit_unit CHECK (limit_unit IS NULL OR limit_unit ~ '^[A-Z][A-Z0-9_]*$'),
    CONSTRAINT chk_obligation_qualifier CHECK (qualifier IS NULL OR (qualifier = btrim(qualifier) AND qualifier <> ''))
);
CREATE INDEX idx_legal_obligations_scope ON legal_obligations (scope_kind, scope);

INSERT INTO jurisdiction_regimes (code, name, citation) VALUES
 ('EU', 'European Union', 'Treaty on European Union');

INSERT INTO jurisdiction_members (regime_code, country, member_from, member_to) VALUES
 ('EU','BE',DATE '1958-01-01',NULL), ('EU','DE',DATE '1958-01-01',NULL), ('EU','FR',DATE '1958-01-01',NULL),
 ('EU','IT',DATE '1958-01-01',NULL), ('EU','LU',DATE '1958-01-01',NULL), ('EU','NL',DATE '1958-01-01',NULL),
 ('EU','DK',DATE '1973-01-01',NULL), ('EU','IE',DATE '1973-01-01',NULL),
 ('EU','GB',DATE '1973-01-01',DATE '2020-01-31'),
 ('EU','GR',DATE '1981-01-01',NULL),
 ('EU','ES',DATE '1986-01-01',NULL), ('EU','PT',DATE '1986-01-01',NULL),
 ('EU','AT',DATE '1995-01-01',NULL), ('EU','FI',DATE '1995-01-01',NULL), ('EU','SE',DATE '1995-01-01',NULL),
 ('EU','CY',DATE '2004-05-01',NULL), ('EU','CZ',DATE '2004-05-01',NULL), ('EU','EE',DATE '2004-05-01',NULL),
 ('EU','HU',DATE '2004-05-01',NULL), ('EU','LV',DATE '2004-05-01',NULL), ('EU','LT',DATE '2004-05-01',NULL),
 ('EU','MT',DATE '2004-05-01',NULL), ('EU','PL',DATE '2004-05-01',NULL), ('EU','SK',DATE '2004-05-01',NULL),
 ('EU','SI',DATE '2004-05-01',NULL),
 ('EU','BG',DATE '2007-01-01',NULL), ('EU','RO',DATE '2007-01-01',NULL),
 ('EU','HR',DATE '2013-07-01',NULL);

INSERT INTO legal_obligations (code, scope_kind, scope, effective_from, effective_to, citation, summary) VALUES
 -- The European Union
 ('GDPR','REGIME','EU',DATE '2018-05-25',NULL,'Regulation (EU) 2016/679',
  'Personal data needs a lawful basis; people may see, correct, erase and take it; a breach is reported within 72 hours.'),
 ('SEPA_ISO20022_BULK','REGIME','EU',DATE '2016-10-31',NULL,'Regulation (EU) 260/2012 art.5(1)(d)',
  'A business sending bundled euro credit transfers sends them as ISO 20022 XML.'),
 ('PRICE_REDUCTION_PRIOR_PRICE','REGIME','EU',DATE '2022-05-28',NULL,'Directive 98/6/EC art.6a, inserted by Directive (EU) 2019/2161',
  'A price reduction is announced against the lowest price charged in the 30 days before it.'),
 ('GPSR_ONLINE_OFFER','REGIME','EU',DATE '2024-12-13',NULL,'Regulation (EU) 2023/988 art.19',
  'An online offer shows the manufacturer, the EU responsible person, product identification and any warnings.'),
 ('GPSR_RECALL_NOTICE','REGIME','EU',DATE '2024-12-13',NULL,'Regulation (EU) 2023/988 arts.35-36',
  'A recall is notified directly to the consumers who can be identified, with a choice of remedy.'),
 ('ACCESSIBLE_ECOMMERCE','REGIME','EU',DATE '2025-06-28',NULL,'Directive (EU) 2019/882 (European Accessibility Act)',
  'A consumer e-commerce service meets the accessibility requirements; in practice WCAG 2.1 AA through EN 301 549.'),
 ('DATA_ACT_SWITCHING','REGIME','EU',DATE '2025-09-12',NULL,'Regulation (EU) 2023/2854 ch.VI',
  'A cloud or SaaS customer can switch provider and take all its exportable data within 30 days.'),
 ('VERIFICATION_OF_PAYEE','REGIME','EU',DATE '2025-10-09',NULL,'Regulation (EU) 2024/886',
  'A euro credit transfer is checked against the payee''s name before it is sent.'),
 ('CRA_VULNERABILITY_REPORTING','REGIME','EU',DATE '2026-09-11',NULL,'Regulation (EU) 2024/2847 art.14',
  'An actively exploited vulnerability or severe incident in a product with digital elements is reported within 24 hours.'),
 ('CASH_PAYMENT_LIMIT','REGIME','EU',DATE '2027-07-10',NULL,'Regulation (EU) 2024/1624',
  'Cash payments for goods or services are capped at EUR 10,000, or lower where a member state sets a lower limit.'),
 ('DEPOSIT_RETURN','REGIME','EU',DATE '2029-01-01',NULL,'Regulation (EU) 2025/40',
  'Deposit return systems run for single-use plastic bottles and metal cans up to three litres.'),
 ('E_INVOICING_CROSS_BORDER','REGIME','EU',DATE '2030-07-01',NULL,'Council Directive (EU) 2025/516 (VAT in the Digital Age)',
  'Cross-border business supplies are invoiced electronically and reported digitally.'),
 -- Directive 98/6/EC art.3: the EU's unit-price rule. The same code as the UK's, so a service asks one
 -- question for any market; without this row a German or French business would be told it owes no unit
 -- price, and the unit price would quietly not be shown where it is law.
 ('UNIT_PRICING','REGIME','EU',DATE '2000-03-18',NULL,'Directive 98/6/EC art.3',
  'The selling price and the unit price of a product offered to consumers are shown, per kilogram, litre, metre, square metre or cubic metre, or per item for goods sold by number.'),
 -- The United Kingdom
 ('UK_GDPR','COUNTRY','GB',DATE '2021-01-01',NULL,'UK GDPR; Data Protection Act 2018',
  'Personal data needs a lawful basis; people may see, correct, erase and take it; a breach is reported within 72 hours.'),
 ('MTD_VAT','COUNTRY','GB',DATE '2022-04-01',NULL,'Making Tax Digital for VAT',
  'VAT records are kept digitally and returns are filed through compatible software.'),
 ('UNIT_PRICING','COUNTRY','GB',DATE '2026-04-06',NULL,'Price Marking Order 2004, as amended',
  'Unit prices are shown legibly in standard metric units, across a wider range of goods.'),
 ('TOBACCO_BIRTH_COHORT','COUNTRY','GB',DATE '2027-01-01',NULL,'Tobacco and Vapes Act 2026',
  'No tobacco is sold to anyone born on or after 1 January 2009.'),
 ('DEPOSIT_RETURN','COUNTRY','GB',DATE '2027-10-01',NULL,'Deposit Scheme for Drinks Containers (England and Northern Ireland) Regulations 2025 (SI 2025/67)',
  'A deposit is charged on in-scope drinks containers in England and Northern Ireland, and refunded on return.'),
 ('E_INVOICING_B2B','COUNTRY','GB',DATE '2029-04-01',NULL,'HMRC, Tax Update 2026 (announced)',
  'Business and public-sector VAT invoices are exchanged as e-invoices over Peppol.'),
 -- Germany
 ('FISCAL_TSE','COUNTRY','DE',DATE '2020-01-01',NULL,'Abgabenordnung s.146a; KassenSichV',
  'Every till transaction is signed by a certified technical security system.'),
 ('E_INVOICING_RECEIVE','COUNTRY','DE',DATE '2025-01-01',NULL,'Umsatzsteuergesetz s.14',
  'Every business can receive EN 16931 e-invoices.'),
 ('E_INVOICING_ISSUE','COUNTRY','DE',DATE '2027-01-01',NULL,'Umsatzsteuergesetz s.14',
  'Businesses with turnover over EUR 800,000 issue e-invoices; every business from 1 January 2028.'),
 -- Germany's Preisangabenverordnung 2022 takes up both member-state options in Directive 98/6/EC art.6a:
 -- art.6a(5), a reduction increased step by step keeps the price before its first step as its prior
 -- price; and art.6a(3), different rules for goods that spoil or expire quickly. pricing-svc applies
 -- neither unless every country whose law reaches the offer has taken it up, so a country is listed
 -- here only where its own law says so.
 ('PRICE_REDUCTION_PROGRESSIVE','COUNTRY','DE',DATE '2022-05-28',NULL,
  'Preisangabenverordnung 2022 §11(3), under Directive 98/6/EC art.6a(5)',
  'A price reduction increased step by step without a break is announced against the lowest price of the 30 days before its first step.'),
 ('PRICE_REDUCTION_PERISHABLE_EXEMPT','COUNTRY','DE',DATE '2022-05-28',NULL,
  'Preisangabenverordnung 2022 §11(4), under Directive 98/6/EC art.6a(3)',
  'Goods that spoil quickly or are near their expiry, reduced because of it, need no prior price when the reason is made clear.'),
 -- France
 ('CERTIFIED_TILL_SOFTWARE','COUNTRY','FR',DATE '2018-01-01',NULL,'Code general des impots art.286 I 3 bis',
  'Till software is certified or attested as unalterable, secured, kept and archived.'),
 ('E_INVOICING_RECEIVE','COUNTRY','FR',DATE '2026-09-01',NULL,'French e-invoicing and e-reporting reform',
  'Every business can receive e-invoices.'),
 ('E_INVOICING_ISSUE','COUNTRY','FR',DATE '2026-09-01',NULL,'French e-invoicing and e-reporting reform',
  'Large and mid-sized businesses issue e-invoices and e-report their sales; small businesses from 1 September 2027.'),
 -- The duty to report the transactions no e-invoice covers is named on the obligations sheet rather than
 -- left inside another row's summary. It is a separate obligation in law: a business that issues every
 -- invoice correctly and reports nothing is still in breach, and a sheet that folded the two together could
 -- not show that.
 ('E_REPORTING','COUNTRY','FR',DATE '2026-09-01',NULL,
  'CGI art. 290 and 290 A; decret n. 2022-1299 du 7 oct. 2022',
  'Transactions no e-invoice covers — sales to consumers and abroad — and the payment data for services are transmitted to the administration through the business''s platform.'),
 -- Belgium, Poland, Spain, Portugal
 ('E_INVOICING_B2B','COUNTRY','BE',DATE '2026-01-01',NULL,'Belgian B2B e-invoicing mandate (Peppol)',
  'Business invoices are exchanged as structured e-invoices over Peppol.'),
 ('E_INVOICING_KSEF','COUNTRY','PL',DATE '2026-02-01',NULL,'Krajowy System e-Faktur (KSeF)',
  'Invoices are issued through KSeF in the FA(3) structure; every other taxpayer from 1 April 2026.'),
 ('VERIFACTU','COUNTRY','ES',DATE '2027-01-01',NULL,'Real Decreto 1007/2023',
  'Billing software records every invoice tamper-evidently; corporate-tax payers first, everyone else from 1 July 2027.'),
 ('CERTIFIED_BILLING','COUNTRY','PT',DATE '2013-01-01',NULL,'Decreto-Lei 198/2012',
  'Invoices come from certified software, chained by signature, and are communicated to the tax authority.'),
 -- India
 ('GST_E_INVOICING','COUNTRY','IN',DATE '2023-08-01',NULL,'CGST Rules r.48(4)',
  'Business invoices are registered on the Invoice Registration Portal when aggregate turnover exceeds INR 5 crore.'),
 ('DPDP','COUNTRY','IN',DATE '2027-05-13',NULL,'Digital Personal Data Protection Act 2023; DPDP Rules 2025',
  'Notice, consent, data principal rights, breach intimation and retention limits apply to personal data.');
