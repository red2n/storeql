-- Assortment by store, and range review.
--
-- Both build on what the catalogue already holds. product_stores (V11__product_store_assortment.sql)
-- records which stores carry a product, with the sensible default that a product with no rows sells
-- everywhere. The item lifecycle (products.status, launch_on and discontinued_at in V1__init.sql)
-- launches, discontinues and reinstates a line against a date. Neither is replaced here.
--
-- What is added is the discipline around the data, and it is the same shape twice: a decision with a
-- date, a reason, and the comparison it was made against. A line is ranged or de-listed store by
-- store, on somebody's judgement; this records why, and what it was weighed against.

-- ── clusters: range by a group of stores, not one at a time ─────────────────────────────────────────
--
-- The practical complaint about per-store assortment is not that it cannot express a range — it is that
-- expressing one costs a row per store. A chain ranges by type: city convenience, superstore, the ten
-- shops with a fish counter. A cluster names that group once so a decision can be taken against it.
--
-- A store may belong to several clusters, deliberately: "Scotland" and "has a bakery" are both true of
-- the same shop, and forcing a single grouping would make one of them unsayable.
CREATE TABLE store_clusters (
    id          UUID        PRIMARY KEY,
    tenant_id   UUID        NOT NULL,
    code        TEXT        NOT NULL,
    name        TEXT        NOT NULL,
    note        TEXT,
    status      TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    created_by  UUID        NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,

    CONSTRAINT chk_cluster_status CHECK (status IN ('ACTIVE', 'RETIRED'))
);

CREATE UNIQUE INDEX uq_store_clusters_code
    ON store_clusters (tenant_id, lower(code))
    WHERE status = 'ACTIVE';

-- store_id is tenant-svc's and carries no foreign key across services (golden rule #1).
CREATE TABLE store_cluster_members (
    cluster_id UUID        NOT NULL REFERENCES store_clusters (id) ON DELETE CASCADE,
    store_id   UUID        NOT NULL,
    tenant_id  UUID        NOT NULL,
    added_at   TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_store_cluster_members PRIMARY KEY (cluster_id, store_id)
);

CREATE INDEX idx_cluster_members_store ON store_cluster_members (tenant_id, store_id);

COMMENT ON TABLE store_clusters IS
    'A named group of stores to range against. A store may belong to several: groupings overlap.';

-- ── range reviews: the comparison behind an add or a drop ────────────────────────────────────────────
--
-- A review looks at one category over a period, ranks its lines on what they actually did, and produces
-- the decisions a buyer signs off. Its value is not the decision — the lifecycle could already launch and
-- discontinue — it is that the figures the decision was taken on are kept beside it. "We de-listed this
-- because it was bottom of the category on margin, and here is the number" is a different thing from
-- "somebody de-listed this".
--
-- Declared before assortment_changes, which refers to it.
CREATE TABLE range_reviews (
    id          UUID        PRIMARY KEY,
    tenant_id   UUID        NOT NULL,
    category_id UUID        NOT NULL REFERENCES categories (id),
    name        TEXT        NOT NULL,
    -- The trading period the figures cover. Inclusive of from, exclusive of to, as every period here is.
    period_from DATE        NOT NULL,
    period_to   DATE        NOT NULL,
    status      TEXT        NOT NULL,
    note        TEXT,
    created_at  TIMESTAMPTZ NOT NULL,
    created_by  UUID        NOT NULL,
    decided_at  TIMESTAMPTZ,
    decided_by  UUID,

    CONSTRAINT chk_review_status CHECK (status IN ('OPEN', 'DECIDED', 'ABANDONED')),
    CONSTRAINT chk_review_period CHECK (period_to > period_from),
    CONSTRAINT chk_review_decided CHECK ((status = 'DECIDED') = (decided_at IS NOT NULL))
);

CREATE INDEX idx_range_reviews_category
    ON range_reviews (tenant_id, category_id, period_from DESC);

COMMENT ON TABLE range_reviews IS
    'A category reviewed over a period. Its worth is the figures kept beside each decision.';

-- ── assortment changes: the decision, dated, with a reason ──────────────────────────────────────────
--
-- product_stores stays exactly as it is: the range as it stands today, which is what the till and the
-- storefront ask. This table is the decision log in front of it. A decision (the line, the action, its
-- date, the reason and who decided) is written once and its facts are never edited, so the question
-- "who took this line out of the Scottish shops, when, and why" can be answered months later. The
-- sweep stamps an outcome on it: applied_at when the change is pushed, or refused_at when it is
-- closed for good (AssortmentRepository.apply, run by the AssortmentSweeper and by
-- POST /admin/assortment/changes/apply). What the table enforces (trg_assortment_changes_guard,
-- below) is that no UPDATE edits a decision and that a row with an outcome is not updated again. It
-- does not know who is updating: an UPDATE of a row with no outcome yet may stamp one, with any
-- values its checks allow, and that only the sweep does so is the code's doing, not the table's.
--
-- Two things follow from having dates. A change can be recorded BEFORE it takes effect, which is how a
-- range is planned rather than typed on the morning it happens; and applying it is a separate step, so
-- the log is the intent and product_stores is the state. `applied_at` is what distinguishes them.
--
-- A range change the sweep refuses for good is closed, not left due for ever. A de-list that would take a
-- line out of the last stores it is sold at can never be applied as it reads: no product_stores rows
-- means every store, the opposite of a de-list. It is refused when it is recorded, judged on the line's
-- whole plan, but the range can still move under it afterwards — a PUT of the product's stores, another
-- change recorded later, a store added to the cluster it aims at — and the sweep would then meet it on
-- every run, refuse it every time, and it would stay due for ever. So the sweep closes it, once, with its
-- reason: refused_at is set in the same transaction that judged it, with the product row locked, and it
-- is never due again. A change is applied or refused, never both. The other refusals (a de-list of a line
-- sold everywhere, a held manager's listing of a line now sold everywhere, an empty cluster) still leave a
-- change due: a range or a membership can still allow those.
CREATE TABLE assortment_changes (
    id             UUID        PRIMARY KEY,
    tenant_id      UUID        NOT NULL,
    product_id     UUID        NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    -- Exactly one of these: a change is aimed at one store or at a cluster of them.
    store_id       UUID,
    cluster_id     UUID        REFERENCES store_clusters (id),
    action         TEXT        NOT NULL,
    effective_from DATE        NOT NULL,
    -- Why. Required, and that is the point of the table: a de-list with no reason is the thing this row
    -- exists to stop being possible.
    reason         TEXT        NOT NULL,
    decided_by     UUID        NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL,
    -- When the change was actually pushed into product_stores. Null until then.
    applied_at     TIMESTAMPTZ,
    -- The review that produced it, when it came from one rather than from a single decision.
    review_id      UUID,
    -- Decided by a manager held to stores. Such a manager may change a line's range only at their own
    -- stores, and never move it to or from "every store" (no product_stores rows): that is for an owner or
    -- a manager of the whole business. The change is judged when it is recorded, but it is applied on its
    -- own day, and the range may have moved in between — a line ranged to another shop when a branch
    -- manager planned to list it at theirs may be sold everywhere by then, and listing it would take it off
    -- every other shelf. So the change keeps who could make it, and the sweep judges it again on the range
    -- it finds. false (the default) reads as a business-wide decision.
    held_to_stores BOOLEAN     NOT NULL DEFAULT false,
    -- When the sweep closed the change as refused for good, with the refusal it was closed with. The three
    -- are set together or not at all, and a change is never both applied and refused.
    refused_at     TIMESTAMPTZ,
    refusal_code   TEXT,
    refusal_detail TEXT,

    CONSTRAINT chk_assortment_action CHECK (action IN ('LIST', 'DELIST')),
    CONSTRAINT chk_assortment_target CHECK (
        (store_id IS NOT NULL AND cluster_id IS NULL)
        OR (store_id IS NULL AND cluster_id IS NOT NULL)
    ),
    CONSTRAINT chk_assortment_reason CHECK (length(btrim(reason)) > 0),
    CONSTRAINT fk_assortment_change_review
        FOREIGN KEY (review_id) REFERENCES range_reviews (id),
    CONSTRAINT chk_assortment_change_refusal CHECK (
        (refused_at IS NULL AND refusal_code IS NULL AND refusal_detail IS NULL)
        OR (refused_at IS NOT NULL AND length(btrim(refusal_code)) > 0
            AND length(btrim(refusal_detail)) > 0)
    ),
    CONSTRAINT chk_assortment_change_settled_once CHECK (
        refused_at IS NULL OR applied_at IS NULL
    )
);

CREATE INDEX idx_assortment_changes_product
    ON assortment_changes (tenant_id, product_id, effective_from DESC);
-- The sweep that applies what is due reads this: everything dated on or before today that is neither
-- applied nor closed as refused.
CREATE INDEX idx_assortment_changes_pending
    ON assortment_changes (tenant_id, effective_from)
    WHERE applied_at IS NULL AND refused_at IS NULL;

-- Not append-only: the sweep stamps applied_at, or refused_at with its refusal, on a row after it is
-- written (AssortmentRepository). A trigger holds the table to that, so no UPDATE the application
-- sends can do more (the table's owner or a superuser can still disable a trigger). An UPDATE may not
-- change anything the decision recorded (the target, the action, its date, the reason, who decided and
-- when, the review, whether a held manager decided it), and may not touch a row that already carries
-- an outcome, so a change is applied or closed once and stays so. Only UPDATE is guarded: a row still
-- goes with its product (ON DELETE CASCADE) and with its business's erasure.
CREATE FUNCTION assortment_changes_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.id, NEW.tenant_id, NEW.product_id, NEW.store_id, NEW.cluster_id, NEW.action,
        NEW.effective_from, NEW.reason, NEW.decided_by, NEW.created_at, NEW.review_id,
        NEW.held_to_stores)
       IS DISTINCT FROM
       (OLD.id, OLD.tenant_id, OLD.product_id, OLD.store_id, OLD.cluster_id, OLD.action,
        OLD.effective_from, OLD.reason, OLD.decided_by, OLD.created_at, OLD.review_id,
        OLD.held_to_stores) THEN
        RAISE EXCEPTION '%: a decision is not edited', TG_TABLE_NAME;
    END IF;
    IF OLD.applied_at IS NOT NULL OR OLD.refused_at IS NOT NULL THEN
        RAISE EXCEPTION '%: an outcome is stamped once', TG_TABLE_NAME;
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_assortment_changes_guard
    BEFORE UPDATE ON assortment_changes
    FOR EACH ROW EXECUTE FUNCTION assortment_changes_guard();

COMMENT ON TABLE assortment_changes IS
    'The dated, reasoned decision log in front of product_stores. A decision is never edited (a trigger refuses it); the sweep stamps applied_at, or refused_at with its refusal, once.';
COMMENT ON COLUMN assortment_changes.held_to_stores IS
    'Decided by a manager held to stores: never applied so as to move the line to or from every store.';
COMMENT ON COLUMN assortment_changes.refused_at IS
    'When the sweep closed the change as refused for good (a de-list of a line''s last stores). Never due again.';
COMMENT ON COLUMN assortment_changes.refusal_code IS
    'The refusal it was closed with, e.g. ASSORTMENT_LAST_STORE.';
COMMENT ON COLUMN assortment_changes.refusal_detail IS
    'The sentence it was closed with, as the sweep reported it.';

-- ── range review lines: one line under review, with the figures it was judged on ─────────────────────
--
-- The figures are a SNAPSHOT supplied when the line is added, not a live read. Deliberate: sales and
-- margin belong to order-svc and reporting-svc, and product-svc does not read another service's tables.
-- A snapshot is also the more useful record — the decision was taken on the numbers as they stood, and
-- a report re-run next year would show different ones and make the decision look arbitrary.
CREATE TABLE range_review_lines (
    id            UUID    PRIMARY KEY,
    tenant_id     UUID    NOT NULL,
    review_id     UUID    NOT NULL REFERENCES range_reviews (id) ON DELETE CASCADE,
    variant_id    UUID    NOT NULL REFERENCES product_variants (id),
    -- What it did over the period, as recorded at review time.
    units_sold    NUMERIC(14, 3),
    -- Money in the line's own currency. A figure's scale is its currency's minor units (ISO 4217), and the
    -- column holds four decimal places, the most any currency gives (CLF, UYW), so every currency's minor
    -- units fit. product-svc holds each figure to its currency's scale when the line is added
    -- (AssortmentService.addLines, through common-service Fx.minorUnits): a figure with more is refused,
    -- never rounded, and the answer writes each at its currency's minor units. Fourteen whole digits, the
    -- most a figure may carry (AssortmentService refuses one of 10^14 or more).
    revenue       NUMERIC(18, 4),
    margin        NUMERIC(18, 4),
    currency      CHAR(3),
    -- Where it came in the category on whatever the buyer ranked by. 1 is best.
    rank_in_category INTEGER,
    -- KEEP, DELIST, INTRODUCE — or null while the review is still being read.
    decision      TEXT,
    decision_note TEXT,
    -- True when the line is protected from a de-list because the business owns the brand. Kept on the
    -- row rather than looked up later: own-brand status can change, and the review should show the
    -- reason as it applied on the day.
    own_brand     BOOLEAN NOT NULL,

    CONSTRAINT chk_review_line_decision CHECK (
        decision IS NULL OR decision IN ('KEEP', 'DELIST', 'INTRODUCE')
    ),
    CONSTRAINT chk_review_line_rank CHECK (rank_in_category IS NULL OR rank_in_category >= 1),
    -- A currency is needed exactly when there is money to put it against.
    CONSTRAINT chk_review_line_currency CHECK (
        (revenue IS NULL AND margin IS NULL) = (currency IS NULL)
    )
);
CREATE INDEX idx_review_lines_tenant_review ON range_review_lines (tenant_id, review_id);

-- A variant appears once in a review: two rows for one line would be two rankings of the same thing.
CREATE UNIQUE INDEX uq_review_line ON range_review_lines (review_id, variant_id);
CREATE INDEX idx_review_lines_review ON range_review_lines (review_id, rank_in_category);

COMMENT ON COLUMN range_review_lines.units_sold IS
    'A snapshot at review time, not a live read: product-svc does not read another service''s tables.';
COMMENT ON COLUMN range_review_lines.revenue IS
    'Money in the line''s currency, at that currency''s minor units (ISO 4217); checked by product-svc.';
COMMENT ON COLUMN range_review_lines.margin IS
    'Money in the line''s currency, at that currency''s minor units (ISO 4217); checked by product-svc.';
