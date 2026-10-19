"""LR Taxi fleet import & analytics worker.

Parses and pre-validates spreadsheets into staging rows for the Java backend, which performs the
authoritative validation and all final calculations. This package never writes mileage or money
figures to the final tables.
"""

__version__ = "1.0.0"
