import React from 'react';

interface NumericProps {
    units: string;
    handleSubmit: (event: React.FormEvent<HTMLFormElement>) => void;
    setStudentAnswer: (studentAnswer: string) => void;
}

const Numeric: React.FC<NumericProps> = ({ units, handleSubmit, setStudentAnswer }) => {
    return (
        <form onSubmit={handleSubmit}>
            <label>
                Your Answer:&nbsp;
                <input type="text" required onChange={(e) => setStudentAnswer(e.target.value)} style={{width: "100px"}} />&nbsp;
                {units && <span dangerouslySetInnerHTML={{ __html: units }} />}
            </label>&nbsp;
            <button className="btn btn-primary" type="submit">Submit</button>
        </form>
    );
}

export default Numeric;