import psycopg
from psycopg.rows import dict_row

from .config import Config


def connect(cfg: Config) -> psycopg.Connection:
    return psycopg.connect(cfg.conninfo, row_factory=dict_row)
