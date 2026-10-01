-- A gift card load says where its value came from (order-svc GiftCardLoaded, optional trailing
-- orderId / source / note): SALE (sold on a paid till sale, naming the order), RETURN, or a hand
-- reason (GOODWILL, PROMOTION, COMPENSATION, MIGRATION) with the manager's note. Loads recorded
-- before it have none of them.
ALTER TABLE gift_card_loads ADD COLUMN order_id UUID;
ALTER TABLE gift_card_loads ADD COLUMN source   TEXT;
ALTER TABLE gift_card_loads ADD COLUMN note     TEXT;
