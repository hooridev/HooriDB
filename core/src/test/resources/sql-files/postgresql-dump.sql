--
-- PostgreSQL database dump
--

\restrict Yk2pXq7Rb0tW9sLm4eHc1uVd8nGf3aJz5oIiKx6yNw

-- Dumped from database version 18.1
-- Dumped by pg_dump version 18.1

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

DROP SCHEMA IF EXISTS file_run CASCADE;
--
-- Name: file_run; Type: SCHEMA; Schema: -; Owner: hoori
--

CREATE SCHEMA file_run;


--
-- Name: touched(); Type: FUNCTION; Schema: file_run; Owner: hoori
--

CREATE FUNCTION file_run.touched() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
  NEW.note := coalesce(NEW.note, 'touched; by trigger');
  RETURN NEW;
END;
$$;


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: item; Type: TABLE; Schema: file_run; Owner: hoori
--

CREATE TABLE file_run.item (
    id integer NOT NULL,
    name text NOT NULL,
    note text,
    data bytea
);


--
-- Name: item_id_seq; Type: SEQUENCE; Schema: file_run; Owner: hoori
--

CREATE SEQUENCE file_run.item_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: item_id_seq; Type: SEQUENCE OWNED BY; Schema: file_run; Owner: hoori
--

ALTER SEQUENCE file_run.item_id_seq OWNED BY file_run.item.id;


--
-- Name: item id; Type: DEFAULT; Schema: file_run; Owner: hoori
--

ALTER TABLE ONLY file_run.item ALTER COLUMN id SET DEFAULT nextval('file_run.item_id_seq'::regclass);


--
-- Data for Name: item; Type: TABLE DATA; Schema: file_run; Owner: hoori
--

COPY file_run.item (id, name, note, data) FROM stdin;
1	Grüße; 'quoted'	\N	\\x00ff
2	tab\there	line\nbreak	\N
3	back\\slash	-- not a comment; /* nor this */	\N
\.


--
-- Name: item_id_seq; Type: SEQUENCE SET; Schema: file_run; Owner: hoori
--

SELECT pg_catalog.setval('file_run.item_id_seq', 3, true);


--
-- Name: item item_pkey; Type: CONSTRAINT; Schema: file_run; Owner: hoori
--

ALTER TABLE ONLY file_run.item
    ADD CONSTRAINT item_pkey PRIMARY KEY (id);


--
-- Name: item item_touched; Type: TRIGGER; Schema: file_run; Owner: hoori
--

CREATE TRIGGER item_touched BEFORE INSERT ON file_run.item FOR EACH ROW EXECUTE FUNCTION file_run.touched();


--
-- Name: TABLE item; Type: COMMENT; Schema: file_run; Owner: hoori
--

COMMENT ON TABLE file_run.item IS 'Rows; of a dump';


--
-- PostgreSQL database dump complete
--

\unrestrict Yk2pXq7Rb0tW9sLm4eHc1uVd8nGf3aJz5oIiKx6yNw

